package io.heapy.kwasm.bindgen.runtime

import io.heapy.kwasm.CallerAwareHostFunction
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.HostCallContext
import io.heapy.kwasm.HostImport
import io.heapy.kwasm.HostSnapshotHooks
import io.heapy.kwasm.HostSnapshotRestore
import io.heapy.kwasm.IndexType
import io.heapy.kwasm.Instance
import io.heapy.kwasm.InstanceScopedHostSnapshotParticipant
import io.heapy.kwasm.Linker
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.SnapshotFormatException
import io.heapy.kwasm.SnapshotStateException
import io.heapy.kwasm.Store
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import io.heapy.kwasm.bindgen.WasmGuestBindingNotInstalledException
import io.heapy.kwasm.bindgen.WasmGuestRuntimeAbi
import io.heapy.kwasm.bindgen.WasmGuestRuntimeLimits
import io.heapy.kwasm.bindgen.WasmGuestRuntimeProtocolException
import io.heapy.kwasm.bindgen.WasmGuestRuntimeRequestCodec
import io.heapy.kwasm.bindgen.WasmImportBinding
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Resource limits for generated guest-to-host import calls.
 *
 * [request] bounds the decoded KWRQ names, arguments, and result. A result
 * occupies one [maxPendingCalls] slot after `begin` returns and until `finish`
 * copies or cancels it.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class KwasmBindgenHostImportLimits(
    public val request: WasmGuestRuntimeLimits = WasmGuestRuntimeLimits(),
    public val maxPendingCalls: Int = 1_024,
    /**
     * Maximum encoded bytes retained by one store's pending-call table.
     *
     * The limit includes the versioned snapshot header and entry metadata, so
     * capture never needs to allocate an unbounded intermediate payload.
     */
    public val maxPendingStateBytes: Int = 64 * 1024 * 1024,
) {
    init {
        require(maxPendingCalls > 0) { "maxPendingCalls must be positive" }
        require(maxPendingStateBytes >= PENDING_SNAPSHOT_HEADER_SIZE) {
            "maxPendingStateBytes must fit the bindgen snapshot header"
        }
    }

    private companion object {
        const val PENDING_SNAPSHOT_HEADER_SIZE: Int = 16
    }
}

/**
 * Stateful registration for generated [WasmImportBinding] implementations.
 *
 * The two raw imports are installed under `kwasm:bindgen/v1`:
 *
 * - `begin(i32, i32) -> i64` copies and decodes one caller-memory request,
 *   invokes exactly one binding, and retains its encoded result.
 * - `finish(i32, i32, i32) -> i32` copies and consumes that result, or
 *   consumes it without copying for the `(id, 0, 0)` cancellation form.
 *
 * One registry may be installed into a linker used for multiple instances.
 * Pending results retain the caller identity, so an id cannot be completed by
 * another instance.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class KwasmBindgenHostImportRegistry(
    bindings: List<WasmImportBinding>,
    private val limits: KwasmBindgenHostImportLimits = KwasmBindgenHostImportLimits(),
) {
    private val routes: Map<Route, WasmImportBinding> = buildRoutes(bindings)
    private val participantOwner: Any = Any()

    private val beginImport: HostImport = HostImport(
        type = BEGIN_TYPE,
        fn = CallerAwareHostFunction(::begin),
    )
    private val finishImport: HostImport = HostImport(
        type = FINISH_TYPE,
        fn = CallerAwareHostFunction(::finish),
    )

    /** Install this registry's exact version 1 imports into [linker]. */
    public fun define(linker: Linker): Linker =
        linker
            .define(
                WasmGuestRuntimeAbi.HOST_IMPORT_MODULE,
                WasmGuestRuntimeAbi.HOST_BEGIN_IMPORT,
                io.heapy.kwasm.ExternalValue.Function(beginImport),
            )
            .define(
                WasmGuestRuntimeAbi.HOST_IMPORT_MODULE,
                WasmGuestRuntimeAbi.HOST_FINISH_IMPORT,
                io.heapy.kwasm.ExternalValue.Function(finishImport),
            )

    /**
     * Attach snapshot safety state to [caller]'s store.
     *
     * Normal calls attach automatically. Call this during target setup before
     * restoring a snapshot made after the registry has previously been used,
     * so the target store has the same host-participant identity.
     */
    public fun attach(caller: Instance) {
        ensureSnapshotParticipant(caller)
    }

    /**
     * Release every retained result belonging to [caller].
     *
     * Generated glue normally releases each result through `finish`; this is
     * the host lifecycle escape hatch when an instance is abandoned.
     */
    public suspend fun cancelPendingCalls(caller: Instance): Int =
        withContext(NonCancellable) {
            findSnapshotParticipant(caller)?.cancel(caller) ?: 0
        }

    private suspend fun begin(
        context: HostCallContext,
        arguments: List<Value>,
    ): List<Value> {
        val snapshotParticipant = ensureSnapshotParticipant(context.caller)
        val requestPointer = arguments.requireI32(0, "begin request pointer")
        val requestSize = arguments.requireI32(1, "begin request size")
        val memory = requireCallerMemory(context.caller)
        val requestBytes = memory.copyRegion(
            pointer = requestPointer,
            length = requestSize,
            contractElement = WasmGuestRuntimeAbi.HOST_BEGIN_IMPORT,
        )
        val request =
            try {
                WasmGuestRuntimeRequestCodec.decode(requestBytes, limits.request)
            } catch (failure: WasmGuestRuntimeProtocolException) {
                throw KwasmBindgenContractException(
                    contractElement = WasmGuestRuntimeAbi.HOST_BEGIN_IMPORT,
                    detail = failure.message ?: "malformed KWRQ request",
                )
            }
        val binding = routes[Route(request.boundary, request.function)]
            ?: throw WasmGuestBindingNotInstalledException(
                boundary = request.boundary,
                function = request.function,
            )
        val result = binding.invoke(request.arguments)
        requireLimit(
            name = "maxResultBytes",
            actual = result.size,
            maximum = limits.request.maxResultBytes,
        )
        val retained = result.copyOf()

        val callId = snapshotParticipant.retain(context.caller, retained)
        val packed =
            (retained.size.toULong() shl 32) or callId.toUInt().toULong()
        return listOf(Value.I64(packed.toLong()))
    }

    private suspend fun finish(
        context: HostCallContext,
        arguments: List<Value>,
    ): List<Value> = withContext(NonCancellable) {
        val callId = arguments.requireI32(0, "finish call id")
        val resultPointer = arguments.requireI32(1, "finish result pointer")
        val resultCapacity = arguments.requireI32(2, "finish result capacity")
        if (callId <= 0) {
            invalid(
                WasmGuestRuntimeAbi.HOST_FINISH_IMPORT,
                "call id ${callId.toUInt()} is not a live bindgen call",
            )
        }

        val participant = findSnapshotParticipant(context.caller)
            ?: invalid(
                WasmGuestRuntimeAbi.HOST_FINISH_IMPORT,
                "call id ${callId.toUInt()} is unknown for this caller",
            )
        participant.finish(
            caller = context.caller,
            callId = callId,
            resultPointer = resultPointer,
            resultCapacity = resultCapacity,
        )
    }

    private fun ensureSnapshotParticipant(
        caller: Instance,
    ): PendingSnapshotParticipant {
        findSnapshotParticipant(caller)?.let { return it }
        val participant = PendingSnapshotParticipant(caller.store)
        caller.store.registerHostSnapshotParticipant(caller, participant)
        return participant
    }

    private fun findSnapshotParticipant(caller: Instance): PendingSnapshotParticipant? {
        val participant = caller.store.hostSnapshotParticipant(
            id = SNAPSHOT_PARTICIPANT_ID,
            instance = caller,
        )
            ?: return null
        val pending = participant as? PendingSnapshotParticipant
            ?: throw SnapshotStateException(
                "host snapshot participant '$SNAPSHOT_PARTICIPANT_ID' is not a " +
                    "kwasm bindgen pending-result table",
            )
        if (pending.owner !== participantOwner) {
            throw SnapshotStateException(
                "this Store is already attached to a different bindgen host-import registry",
            )
        }
        return pending
    }

    private fun requireCallerMemory(caller: Instance): MemoryInstance {
        val memory =
            caller.exportedMemory(WasmGuestRuntimeAbi.LEGACY_MEMORY_EXPORT)
                ?: caller.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT)
                ?: invalid(
                    contractElement = "caller memory",
                    detail = "guest must export wasm32 memory as " +
                        "'${WasmGuestRuntimeAbi.MEMORY_EXPORT}' (or legacy alias " +
                        "'${WasmGuestRuntimeAbi.LEGACY_MEMORY_EXPORT}')",
                )
        if (memory.indexType != IndexType.I32) {
            invalid(
                contractElement = "caller memory",
                detail = "memory64 is not supported by bindgen version " +
                    WasmGuestRuntimeAbi.VERSION,
            )
        }
        return memory
    }

    private fun MemoryInstance.copyRegion(
        pointer: Int,
        length: Int,
        contractElement: String,
    ): ByteArray {
        requireRegion(pointer, length, contractElement)
        return data().copyOfRange(pointer, pointer + length)
    }

    private fun MemoryInstance.requireRegion(
        pointer: Int,
        length: Int,
        contractElement: String,
    ) {
        if (
            pointer < 0 ||
            length < 0 ||
            pointer > byteSize ||
            length > byteSize - pointer
        ) {
            invalid(
                contractElement,
                "region [${pointer.toUInt()}, " +
                    "${pointer.toUInt().toULong() + length.toUInt().toULong()}) " +
                    "exceeds caller memory size $byteSize",
            )
        }
    }

    private fun List<Value>.requireI32(index: Int, contractElement: String): Int =
        (getOrNull(index) as? Value.I32)?.v
            ?: invalid(
                contractElement,
                "expected i32 argument ${index + 1}, received ${getOrNull(index)}",
            )

    private fun requireLimit(name: String, actual: Int, maximum: Int) {
        if (actual > maximum) {
            throw KwasmBindgenLimitExceeded(
                limit = name,
                actual = actual.toLong(),
                maximum = maximum.toLong(),
            )
        }
    }

    private fun buildRoutes(bindings: List<WasmImportBinding>): Map<Route, WasmImportBinding> {
        val result = linkedMapOf<Route, WasmImportBinding>()
        bindings.forEach { binding ->
            val route = Route(binding.boundary, binding.function)
            if (result.put(route, binding) != null) {
                invalid(
                    contractElement = "host import bindings",
                    detail = "duplicate route '${binding.boundary}'/'${binding.function}'",
                )
            }
        }
        return result
    }

    private data class Route(
        val boundary: String,
        val function: String,
    )

    private data class PendingResult(
        val caller: Instance,
        val bytes: ByteArray,
    )

    private data class PendingTableState(
        val nextCallId: Int = 1,
        val entries: Map<Int, PendingResult> = emptyMap(),
        val resultBytes: Long = 0,
    )

    private data class CallIdAllocation(
        val id: Int,
        val nextCallId: Int,
    )

    private data class DecodedPendingState(
        val nextCallId: Int,
        val entries: Map<Int, ByteArray>,
        val resultBytes: Long,
    )

    private inner class PendingSnapshotParticipant(
        private val store: Store,
    ) : InstanceScopedHostSnapshotParticipant {
        val owner: Any = participantOwner
        private val stateMutex: Mutex = Mutex()
        private val state: MutableStateFlow<PendingTableState> =
            MutableStateFlow(PendingTableState())

        override val id: String
            get() = SNAPSHOT_PARTICIPANT_ID

        suspend fun retain(caller: Instance, bytes: ByteArray): Int =
            stateMutex.withLock {
                check(caller.store === store) {
                    "bindgen pending result belongs to a different store"
                }
                val current = state.value
                if (current.entries.size >= limits.maxPendingCalls) {
                    throw KwasmBindgenLimitExceeded(
                        limit = "maxPendingCalls",
                        actual = current.entries.size.toLong() + 1,
                        maximum = limits.maxPendingCalls.toLong(),
                    )
                }
                val projectedBytes =
                    pendingPayloadSize(
                        entryCount = current.entries.size + 1,
                        resultBytes = current.resultBytes + bytes.size,
                    )
                if (projectedBytes > limits.maxPendingStateBytes) {
                    throw KwasmBindgenLimitExceeded(
                        limit = "maxPendingStateBytes",
                        actual = projectedBytes,
                        maximum = limits.maxPendingStateBytes.toLong(),
                    )
                }
                val allocation = allocateCallId(current)
                state.value = PendingTableState(
                    nextCallId = allocation.nextCallId,
                    entries = current.entries + (
                        allocation.id to PendingResult(caller, bytes.copyOf())
                    ),
                    resultBytes = current.resultBytes + bytes.size,
                )
                allocation.id
            }

        suspend fun finish(
            caller: Instance,
            callId: Int,
            resultPointer: Int,
            resultCapacity: Int,
        ): List<Value> =
            stateMutex.withLock {
                val current = state.value
                val retained = current.entries[callId]
                if (retained == null || retained.caller !== caller) {
                    invalid(
                        WasmGuestRuntimeAbi.HOST_FINISH_IMPORT,
                        "call id ${callId.toUInt()} is unknown for this caller",
                    )
                }

                if (resultPointer == 0 && resultCapacity == 0) {
                    state.value = current.without(callId, retained.bytes.size)
                    return@withLock listOf(Value.I32(0))
                }
                if (resultCapacity < retained.bytes.size) {
                    invalid(
                        WasmGuestRuntimeAbi.HOST_FINISH_IMPORT,
                        "result capacity ${resultCapacity.toUInt()} is smaller than " +
                            "the ${retained.bytes.size}-byte pending result",
                    )
                }

                val memory = requireCallerMemory(caller)
                memory.requireRegion(
                    pointer = resultPointer,
                    length = retained.bytes.size,
                    contractElement = WasmGuestRuntimeAbi.HOST_FINISH_IMPORT,
                )
                retained.bytes.copyInto(memory.data(), destinationOffset = resultPointer)
                state.value = current.without(callId, retained.bytes.size)
                listOf(Value.I32(retained.bytes.size))
            }

        suspend fun cancel(caller: Instance): Int =
            stateMutex.withLock {
                val current = state.value
                val removed = current.entries.filterValues { it.caller === caller }
                if (removed.isEmpty()) return@withLock 0
                val removedBytes = removed.values.sumOf { it.bytes.size.toLong() }
                state.value = PendingTableState(
                    nextCallId = current.nextCallId,
                    entries = current.entries - removed.keys,
                    resultBytes = current.resultBytes - removedBytes,
                )
                removed.size
            }

        override fun capture(
            instance: Instance,
            hooks: HostSnapshotHooks?,
        ): ByteArray {
            if (instance.store !== store) {
                throw SnapshotStateException(
                    "bindgen snapshot participant is attached to a different store",
                )
            }
            val captured = state.value
            val foreignIds = captured.entries
                .filterValues { it.caller !== instance }
                .keys
                .sorted()
            if (foreignIds.isNotEmpty()) {
                throw SnapshotStateException(
                    "cannot snapshot bindgen state for this instance: the store " +
                        "contains pending results owned by another caller " +
                        "(call ids ${foreignIds.joinToString()})",
                )
            }
            return encodePendingState(captured)
        }

        override fun prepareRestore(
            payload: ByteArray,
            instance: Instance,
            hooks: HostSnapshotHooks?,
        ): HostSnapshotRestore {
            if (instance.store !== store) {
                throw SnapshotStateException(
                    "bindgen snapshot participant is attached to a different store",
                )
            }
            val decoded = decodePendingState(payload)
            val current = state.value
            if (current.entries.isNotEmpty()) {
                throw SnapshotStateException(
                    "target bindgen host-import registry is not pristine; " +
                        "${current.entries.size} pending results must be released before restore",
                )
            }
            val restoredEntries = decoded.entries.mapValues { (_, bytes) ->
                PendingResult(instance, bytes.copyOf())
            }
            val restored = PendingTableState(
                nextCallId = decoded.nextCallId,
                entries = restoredEntries,
                resultBytes = decoded.resultBytes,
            )
            return HostSnapshotRestore {
                state.value = restored
            }
        }

        private fun PendingTableState.without(
            callId: Int,
            byteCount: Int,
        ): PendingTableState =
            PendingTableState(
                nextCallId = nextCallId,
                entries = entries - callId,
                resultBytes = resultBytes - byteCount,
            )

        private fun allocateCallId(current: PendingTableState): CallIdAllocation {
            var candidate = current.nextCallId
            var attempts = 0
            while (attempts <= current.entries.size) {
                val next = if (candidate == Int.MAX_VALUE) 1 else candidate + 1
                if (candidate !in current.entries) {
                    return CallIdAllocation(candidate, next)
                }
                candidate = next
                attempts++
            }
            throw KwasmBindgenLimitExceeded(
                limit = "call id space",
                actual = current.entries.size.toLong() + 1,
                maximum = Int.MAX_VALUE.toLong(),
            )
        }

        private fun encodePendingState(captured: PendingTableState): ByteArray {
            val payloadSize = pendingPayloadSize(
                entryCount = captured.entries.size,
                resultBytes = captured.resultBytes,
            )
            if (payloadSize > limits.maxPendingStateBytes || payloadSize > Int.MAX_VALUE) {
                throw SnapshotStateException(
                    "bindgen pending-result state is $payloadSize bytes; " +
                        "maxPendingStateBytes is ${limits.maxPendingStateBytes}",
                )
            }
            val writer = PendingStateWriter(payloadSize.toInt())
            writer.bytes(PENDING_SNAPSHOT_MAGIC)
            writer.u8(PENDING_SNAPSHOT_VERSION)
            writer.u8(0)
            writer.u16(0)
            writer.u32(captured.nextCallId)
            writer.u32(captured.entries.size)
            captured.entries.entries.sortedBy { it.key }.forEach { (callId, retained) ->
                writer.u8(TARGET_CALLER_ASSOCIATION)
                writer.u8(0)
                writer.u16(0)
                writer.u32(callId)
                writer.u32(retained.bytes.size)
                writer.bytes(retained.bytes)
            }
            return writer.result()
        }

        private fun decodePendingState(payload: ByteArray): DecodedPendingState {
            if (payload.size > limits.maxPendingStateBytes) {
                throw SnapshotFormatException(
                    "kwasm bindgen pending-state payload is ${payload.size} bytes; " +
                        "maxPendingStateBytes is ${limits.maxPendingStateBytes}",
                )
            }
            val reader = PendingStateReader(payload)
            reader.requireMagic(PENDING_SNAPSHOT_MAGIC)
            val version = reader.u8("snapshot version")
            if (version != PENDING_SNAPSHOT_VERSION) {
                throw SnapshotFormatException(
                    "unsupported kwasm bindgen pending-state version $version",
                )
            }
            if (
                reader.u8("snapshot flags") != 0 ||
                reader.u16("snapshot reserved field") != 0
            ) {
                throw SnapshotFormatException(
                    "kwasm bindgen pending-state reserved fields must be zero",
                )
            }
            val nextCallId = reader.positiveInt("next call id")
            val count = reader.nonNegativeInt("pending result count")
            if (count > limits.maxPendingCalls) {
                throw SnapshotFormatException(
                    "kwasm bindgen pending result count $count exceeds " +
                        "maxPendingCalls ${limits.maxPendingCalls}",
                )
            }
            val minimumEntryBytes = count.toLong() * PENDING_SNAPSHOT_ENTRY_SIZE
            if (minimumEntryBytes > reader.remaining.toLong()) {
                throw SnapshotFormatException(
                    "truncated kwasm bindgen pending-state entry table",
                )
            }

            val entries = linkedMapOf<Int, ByteArray>()
            var resultBytes = 0L
            repeat(count) { entryIndex ->
                val association = reader.u8("entry $entryIndex caller association")
                if (association != TARGET_CALLER_ASSOCIATION) {
                    throw SnapshotFormatException(
                        "entry $entryIndex has foreign caller association $association",
                    )
                }
                if (
                    reader.u8("entry $entryIndex flags") != 0 ||
                    reader.u16("entry $entryIndex reserved field") != 0
                ) {
                    throw SnapshotFormatException(
                        "entry $entryIndex reserved fields must be zero",
                    )
                }
                val callId = reader.positiveInt("entry $entryIndex call id")
                if (callId in entries) {
                    throw SnapshotFormatException(
                        "duplicate kwasm bindgen pending call id $callId",
                    )
                }
                val resultSize = reader.nonNegativeInt("entry $entryIndex result size")
                if (resultSize > limits.request.maxResultBytes) {
                    throw SnapshotFormatException(
                        "entry $entryIndex result size $resultSize exceeds " +
                            "maxResultBytes ${limits.request.maxResultBytes}",
                    )
                }
                val bytes = reader.bytes(resultSize, "entry $entryIndex result")
                resultBytes += resultSize
                entries[callId] = bytes
            }
            reader.requireEnd()
            return DecodedPendingState(nextCallId, entries, resultBytes)
        }
    }

    private companion object {
        const val SNAPSHOT_PARTICIPANT_ID: String =
            "io.heapy.kwasm.bindgen.v1-pending-results"
        const val PENDING_SNAPSHOT_VERSION: Int = 1
        const val TARGET_CALLER_ASSOCIATION: Int = 1
        const val PENDING_SNAPSHOT_HEADER_SIZE: Int = 16
        const val PENDING_SNAPSHOT_ENTRY_SIZE: Int = 12
        val PENDING_SNAPSHOT_MAGIC: ByteArray =
            byteArrayOf('K'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
        val BEGIN_TYPE: FuncType = FuncType(
            params = listOf(ValType.I32, ValType.I32),
            results = listOf(ValType.I64),
        )
        val FINISH_TYPE: FuncType = FuncType(
            params = listOf(ValType.I32, ValType.I32, ValType.I32),
            results = listOf(ValType.I32),
        )

        fun pendingPayloadSize(entryCount: Int, resultBytes: Long): Long =
            PENDING_SNAPSHOT_HEADER_SIZE.toLong() +
                entryCount.toLong() * PENDING_SNAPSHOT_ENTRY_SIZE +
                resultBytes
    }
}

private class PendingStateWriter(size: Int) {
    private val output: ByteArray = ByteArray(size)
    private var offset: Int = 0

    fun u8(value: Int) {
        output[offset++] = value.toByte()
    }

    fun u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Int) {
        u8(value)
        u8(value ushr 8)
        u8(value ushr 16)
        u8(value ushr 24)
    }

    fun bytes(value: ByteArray) {
        value.copyInto(output, destinationOffset = offset)
        offset += value.size
    }

    fun result(): ByteArray {
        check(offset == output.size)
        return output
    }
}

private class PendingStateReader(
    private val input: ByteArray,
) {
    private var offset: Int = 0
    val remaining: Int get() = input.size - offset

    fun requireMagic(expected: ByteArray) {
        val actual = bytes(expected.size, "snapshot magic")
        if (!actual.contentEquals(expected)) {
            throw SnapshotFormatException("invalid kwasm bindgen pending-state magic")
        }
    }

    fun u8(label: String): Int {
        requireAvailable(1, label)
        return input[offset++].toInt() and 0xFF
    }

    fun u16(label: String): Int =
        u8(label) or (u8(label) shl 8)

    fun positiveInt(label: String): Int {
        val value = u32(label)
        if (value == 0u || value > Int.MAX_VALUE.toUInt()) {
            throw SnapshotFormatException("$label must be in 1..${Int.MAX_VALUE}")
        }
        return value.toInt()
    }

    fun nonNegativeInt(label: String): Int {
        val value = u32(label)
        if (value > Int.MAX_VALUE.toUInt()) {
            throw SnapshotFormatException("$label exceeds ${Int.MAX_VALUE}")
        }
        return value.toInt()
    }

    fun bytes(size: Int, label: String): ByteArray {
        requireAvailable(size, label)
        val result = input.copyOfRange(offset, offset + size)
        offset += size
        return result
    }

    fun requireEnd() {
        if (offset != input.size) {
            throw SnapshotFormatException(
                "kwasm bindgen pending-state payload has ${input.size - offset} trailing bytes",
            )
        }
    }

    private fun u32(label: String): UInt =
        u8(label).toUInt() or
            (u8(label).toUInt() shl 8) or
            (u8(label).toUInt() shl 16) or
            (u8(label).toUInt() shl 24)

    private fun requireAvailable(size: Int, label: String) {
        if (size < 0 || size > input.size - offset) {
            throw SnapshotFormatException(
                "truncated kwasm bindgen pending-state $label",
            )
        }
    }
}

/**
 * Create and install a generated host-import registry into this linker.
 *
 * Use [KwasmBindgenHostImportRegistry] directly when the host needs the
 * explicit abandoned-instance cleanup API.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Linker.defineBindgenHostImports(
    bindings: List<WasmImportBinding>,
    limits: KwasmBindgenHostImportLimits = KwasmBindgenHostImportLimits(),
): Linker = KwasmBindgenHostImportRegistry(bindings, limits).define(this)

private fun invalid(contractElement: String, detail: String): Nothing =
    throw KwasmBindgenContractException(contractElement, detail)
