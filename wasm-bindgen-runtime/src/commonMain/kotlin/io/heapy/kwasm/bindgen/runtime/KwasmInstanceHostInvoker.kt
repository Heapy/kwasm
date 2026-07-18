package io.heapy.kwasm.bindgen.runtime

import io.heapy.kwasm.ExportedFunction
import io.heapy.kwasm.IndexType
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Interpreter
import io.heapy.kwasm.Machine
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import io.heapy.kwasm.bindgen.WasmGuestRuntimeAbi
import io.heapy.kwasm.bindgen.WasmHostInvoker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Version 1 export names and request layout used by [KwasmInstanceHostInvoker].
 *
 * The guest owns every allocation. The host allocates and fills one request,
 * calls [INVOKE_EXPORT], copies the returned allocation, and releases both
 * allocations through [FREE_EXPORT].
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public object KwasmBindgenRuntimeAbi {
    public const val VERSION: Int = WasmGuestRuntimeAbi.VERSION
    public const val REQUEST_HEADER_SIZE: Int = WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE

    public const val MEMORY_EXPORT: String = WasmGuestRuntimeAbi.MEMORY_EXPORT
    public const val LEGACY_MEMORY_EXPORT: String = WasmGuestRuntimeAbi.LEGACY_MEMORY_EXPORT
    public const val ALLOCATE_EXPORT: String = WasmGuestRuntimeAbi.ALLOCATE_EXPORT
    public const val FREE_EXPORT: String = WasmGuestRuntimeAbi.FREE_EXPORT
    public const val INVOKE_EXPORT: String = WasmGuestRuntimeAbi.INVOKE_EXPORT
}

/** Host-side resource limits applied before bytes are copied from or to a guest. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class KwasmBindgenRuntimeLimits(
    public val maxBoundaryNameBytes: Int = 64 * 1024,
    public val maxFunctionNameBytes: Int = 64 * 1024,
    public val maxArgumentsBytes: Int = 16 * 1024 * 1024,
    public val maxResultBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(maxBoundaryNameBytes >= 0) { "maxBoundaryNameBytes must be non-negative" }
        require(maxFunctionNameBytes >= 0) { "maxFunctionNameBytes must be non-negative" }
        require(maxArgumentsBytes >= 0) { "maxArgumentsBytes must be non-negative" }
        require(maxResultBytes >= 0) { "maxResultBytes must be non-negative" }
    }
}

/** Base type for a malformed or resource-violating bindgen runtime contract. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class KwasmBindgenRuntimeException(message: String) : IllegalStateException(message)

/** A required guest export, signature, pointer, or ownership rule is invalid. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class KwasmBindgenContractException(
    public val contractElement: String,
    detail: String,
) : KwasmBindgenRuntimeException("Invalid kwasm bindgen contract for $contractElement: $detail")

/** A configured host-side bindgen transport limit was exceeded. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class KwasmBindgenLimitExceeded(
    public val limit: String,
    public val actual: Long,
    public val maximum: Long,
) : KwasmBindgenRuntimeException(
    "kwasm bindgen limit '$limit' exceeded: $actual > $maximum",
)

/**
 * Bridge used only for non-suspending generated boundary functions.
 *
 * The portable adapter cannot choose how a host target blocks a thread. JVM
 * callers normally use `JvmRunBlockingWasmBridge`; other targets may supply a
 * host-appropriate bridge. Suspend boundary functions bypass this bridge.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface WasmBlockingBridge {
    public fun execute(block: suspend () -> ByteArray): ByteArray
}

/**
 * Concrete [WasmHostInvoker] backed by a kwasm [Instance].
 *
 * Construction validates the complete version 1 guest export contract.
 * Calls are serialized because an [Instance]'s store is coroutine-confined and
 * non-reentrant. Guest traps and host cancellation propagate unchanged.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class KwasmInstanceHostInvoker(
    private val instance: Instance,
    private val blockingBridge: WasmBlockingBridge,
    private val machine: Machine = Interpreter(),
    private val limits: KwasmBindgenRuntimeLimits = KwasmBindgenRuntimeLimits(),
) : WasmHostInvoker {
    private val memory: MemoryInstance = requireMemory()
    private val allocateFunction: ExportedFunction = requireFunction(
        KwasmBindgenRuntimeAbi.ALLOCATE_EXPORT,
        params = listOf(ValType.I32),
        results = listOf(ValType.I32),
    )
    private val freeFunction: ExportedFunction = requireFunction(
        KwasmBindgenRuntimeAbi.FREE_EXPORT,
        params = listOf(ValType.I32, ValType.I32),
        results = emptyList(),
    )
    private val invokeFunction: ExportedFunction = requireFunction(
        KwasmBindgenRuntimeAbi.INVOKE_EXPORT,
        params = listOf(ValType.I32, ValType.I32),
        results = listOf(ValType.I64),
    )
    private val callMutex: Mutex = Mutex()

    override fun invoke(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray = blockingBridge.execute {
        invokeSuspend(boundary, function, arguments)
    }

    override suspend fun invokeSuspend(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray = callMutex.withLock {
        invokeLocked(boundary, function, arguments)
    }

    private suspend fun invokeLocked(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray {
        val request = encodeRequest(boundary, function, arguments)
        var requestRegion: GuestRegion? = null
        var resultRegion: GuestRegion? = null
        var primaryFailure: Throwable? = null

        try {
            requestRegion = allocate(request.size, "request allocation")
            request.copyInto(
                destination = memory.data(),
                destinationOffset = requestRegion.pointer,
            )

            val packedResult = requireI64Result(
                function = invokeFunction,
                arguments = listOf(
                    Value.I32(requestRegion.pointer),
                    Value.I32(requestRegion.length),
                ),
                contractElement = KwasmBindgenRuntimeAbi.INVOKE_EXPORT,
            )
            val candidate = decodeResultRegion(packedResult)
            requireDisjointAllocations(requestRegion, candidate)
            requireRegionInMemory(candidate, "result allocation")
            resultRegion = candidate
            requireLimit("maxResultBytes", candidate.length, limits.maxResultBytes)

            return memory.data().copyOfRange(
                fromIndex = candidate.pointer,
                toIndex = candidate.pointer + candidate.length,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            resultRegion?.let { region ->
                try {
                    release(region)
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            requestRegion?.let { region ->
                try {
                    release(region)
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }

            if (primaryFailure != null) {
                cleanupFailures.forEach(primaryFailure::addSuppressed)
            } else if (cleanupFailures.isNotEmpty()) {
                val cleanupFailure = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                throw cleanupFailure
            }
        }
    }

    private fun encodeRequest(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray {
        val boundaryBytes = encodeWireName(boundary, "boundary wire name")
        val functionBytes = encodeWireName(function, "function wire name")
        requireLimit(
            "maxBoundaryNameBytes",
            boundaryBytes.size,
            limits.maxBoundaryNameBytes,
        )
        requireLimit(
            "maxFunctionNameBytes",
            functionBytes.size,
            limits.maxFunctionNameBytes,
        )
        requireLimit("maxArgumentsBytes", arguments.size, limits.maxArgumentsBytes)

        val requestSize =
            KwasmBindgenRuntimeAbi.REQUEST_HEADER_SIZE.toLong() +
                boundaryBytes.size +
                functionBytes.size +
                arguments.size
        if (requestSize > Int.MAX_VALUE) {
            throw KwasmBindgenLimitExceeded(
                limit = "request address space",
                actual = requestSize,
                maximum = Int.MAX_VALUE.toLong(),
            )
        }

        return ByteArray(requestSize.toInt()).also { request ->
            request[0] = 0x4B
            request[1] = 0x57
            request[2] = 0x52
            request[3] = 0x51
            request[4] = KwasmBindgenRuntimeAbi.VERSION.toByte()
            request.writeIntLittleEndian(8, boundaryBytes.size)
            request.writeIntLittleEndian(12, functionBytes.size)
            request.writeIntLittleEndian(16, arguments.size)

            var offset = KwasmBindgenRuntimeAbi.REQUEST_HEADER_SIZE
            boundaryBytes.copyInto(request, destinationOffset = offset)
            offset += boundaryBytes.size
            functionBytes.copyInto(request, destinationOffset = offset)
            offset += functionBytes.size
            arguments.copyInto(request, destinationOffset = offset)
        }
    }

    private fun encodeWireName(value: String, contractElement: String): ByteArray {
        val bytes = value.encodeToByteArray()
        if (bytes.decodeToString(throwOnInvalidSequence = true) != value) {
            throw KwasmBindgenContractException(
                contractElement,
                "value is not a valid Unicode scalar sequence",
            )
        }
        return bytes
    }

    private suspend fun allocate(size: Int, contractElement: String): GuestRegion {
        val pointer = requireI32Result(
            function = allocateFunction,
            arguments = listOf(Value.I32(size)),
            contractElement = KwasmBindgenRuntimeAbi.ALLOCATE_EXPORT,
        )
        val region = GuestRegion(pointer = pointer, length = size)
        requireRegionInMemory(region, contractElement)
        return region
    }

    private suspend fun release(region: GuestRegion) {
        val results = freeFunction.invoke(
            arguments = listOf(Value.I32(region.pointer), Value.I32(region.length)),
            machine = machine,
        )
        if (results.isNotEmpty()) {
            throw KwasmBindgenContractException(
                KwasmBindgenRuntimeAbi.FREE_EXPORT,
                "expected no results, received ${results.size}",
            )
        }
    }

    private suspend fun requireI32Result(
        function: ExportedFunction,
        arguments: List<Value>,
        contractElement: String,
    ): Int {
        val results = function.invoke(arguments, machine)
        val result = results.singleOrNull() as? Value.I32
            ?: throw KwasmBindgenContractException(
                contractElement,
                "expected one i32 result, received ${results.describe()}",
            )
        if (result.v < 0) {
            throw KwasmBindgenContractException(
                contractElement,
                "returned pointer ${result.v.toUInt()} outside this backend's address space",
            )
        }
        return result.v
    }

    private suspend fun requireI64Result(
        function: ExportedFunction,
        arguments: List<Value>,
        contractElement: String,
    ): Long {
        val results = function.invoke(arguments, machine)
        return (results.singleOrNull() as? Value.I64)?.v
            ?: throw KwasmBindgenContractException(
                contractElement,
                "expected one i64 result, received ${results.describe()}",
            )
    }

    private fun decodeResultRegion(packed: Long): GuestRegion {
        val bits = packed.toULong()
        val pointer = bits and UINT32_MASK
        val length = bits shr 32
        if (pointer > Int.MAX_VALUE.toULong()) {
            throw KwasmBindgenContractException(
                KwasmBindgenRuntimeAbi.INVOKE_EXPORT,
                "returned pointer $pointer outside this backend's address space",
            )
        }
        if (length > Int.MAX_VALUE.toULong()) {
            throw KwasmBindgenContractException(
                KwasmBindgenRuntimeAbi.INVOKE_EXPORT,
                "returned length $length outside this backend's address space",
            )
        }
        return GuestRegion(pointer.toInt(), length.toInt())
    }

    private fun requireRegionInMemory(region: GuestRegion, contractElement: String) {
        val byteSize = memory.byteSize
        if (
            region.pointer > byteSize ||
            region.length > byteSize - region.pointer
        ) {
            throw KwasmBindgenContractException(
                contractElement,
                "region [${region.pointer}, ${region.endExclusive}) exceeds " +
                    "linear memory size $byteSize",
            )
        }
    }

    private fun requireDisjointAllocations(request: GuestRegion, result: GuestRegion) {
        val overlaps =
            request.pointer == result.pointer ||
                (
                    request.pointer.toLong() < result.endExclusive &&
                        result.pointer.toLong() < request.endExclusive
                    )
        if (overlaps) {
            throw KwasmBindgenContractException(
                KwasmBindgenRuntimeAbi.INVOKE_EXPORT,
                "result allocation [${result.pointer}, ${result.endExclusive}) overlaps " +
                    "request allocation [${request.pointer}, ${request.endExclusive})",
            )
        }
    }

    private fun requireMemory(): MemoryInstance {
        val acceptedNames = listOf(
            KwasmBindgenRuntimeAbi.LEGACY_MEMORY_EXPORT,
            KwasmBindgenRuntimeAbi.MEMORY_EXPORT,
        )
        var exportName: String? = null
        var memory: MemoryInstance? = null
        acceptedNames.forEach { candidate ->
            if (memory == null) {
                instance.exportedMemory(candidate)?.let {
                    exportName = candidate
                    memory = it
                }
            }
        }
        val resolvedName = exportName
            ?: throw KwasmBindgenContractException(
                KwasmBindgenRuntimeAbi.MEMORY_EXPORT,
                "required memory export is missing; accepted names are " +
                    acceptedNames.joinToString(),
            )
        val resolvedMemory = checkNotNull(memory)
        if (resolvedMemory.indexType != IndexType.I32) {
            throw KwasmBindgenContractException(
                resolvedName,
                "memory64 is not supported by version ${KwasmBindgenRuntimeAbi.VERSION}",
            )
        }
        return resolvedMemory
    }

    private fun requireFunction(
        name: String,
        params: List<ValType>,
        results: List<ValType>,
    ): ExportedFunction {
        val function = instance.exportedFunction(name)
            ?: throw KwasmBindgenContractException(
                name,
                if (instance.export(name) == null) {
                    "required function export is missing"
                } else {
                    "export is not a function"
                },
            )
        if (function.type.params != params || function.type.results != results) {
            throw KwasmBindgenContractException(
                name,
                "expected (func${params.describeTypes("param")}${results.describeTypes("result")}), " +
                    "received ${function.type}",
            )
        }
        return function
    }

    private fun requireLimit(name: String, actual: Int, maximum: Int) {
        if (actual > maximum) {
            throw KwasmBindgenLimitExceeded(
                limit = name,
                actual = actual.toLong(),
                maximum = maximum.toLong(),
            )
        }
    }

    private data class GuestRegion(
        val pointer: Int,
        val length: Int,
    ) {
        val endExclusive: Long
            get() = pointer.toLong() + length
    }

    private companion object {
        val UINT32_MASK: ULong = 0xFFFF_FFFFuL
    }
}

private fun ByteArray.writeIntLittleEndian(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

private fun List<Value>.describe(): String =
    if (isEmpty()) "no results" else joinToString(prefix = "[", postfix = "]")

private fun List<ValType>.describeTypes(kind: String): String =
    joinToString(separator = "") { " ($kind $it)" }
