package io.heapy.kwasm.snapshot

import io.heapy.kwasm.ArrayObject
import io.heapy.kwasm.ExecutionLimits
import io.heapy.kwasm.ExecutionListener
import io.heapy.kwasm.FuelExhaustionPolicy
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.GuestException
import io.heapy.kwasm.HostImport
import io.heapy.kwasm.HostSnapshotHooks
import io.heapy.kwasm.HostSnapshotRestore
import io.heapy.kwasm.Instance
import io.heapy.kwasm.InstanceScopedHostSnapshotParticipant
import io.heapy.kwasm.Module
import io.heapy.kwasm.PauseHandle
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.RuntimeInstanceSnapshot
import io.heapy.kwasm.RuntimeStoreSnapshot
import io.heapy.kwasm.SnapshotFormatException
import io.heapy.kwasm.SnapshotModuleMismatch
import io.heapy.kwasm.SnapshotStateException
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.StoreStatus
import io.heapy.kwasm.StructObject
import io.heapy.kwasm.UncaughtWasmException
import io.heapy.kwasm.Value
import io.heapy.kwasm.wat.WatComposer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotTest {
    @Test
    fun suspendedStateRoundTripsWithExactModuleHashAndExternHooks(): Unit = runBlocking {
        val module = Module.decode(suspendModuleBytes())
        val release = CompletableDeferred<Unit>()
        val imports = ResolvedImports(
            functions = listOf(
                HostImport(FuncType(emptyList(), emptyList())) {
                    release.await()
                    emptyList()
                },
            ),
        )
        val store = snapshotStore()
        val instance = Instance(store, module, imports)
        instance.memories.single().storeByte(17, 0xAB)
        instance.tables.single().set(0, Value.Ref.Extern(77))
        instance.globals[0].set(Value.Ref.Extern(77))
        instance.globals[1].set(Value.F32(Float.fromBits(0x7FA1_2345)))

        val invocation = async { instance.invoke("run") }
        store.status.first { it == StoreStatus.InHostImport }
        val hooks = integerHooks()
        val encoded = KwasmSnapshot.capture(instance, hooks)
        val header = KwasmSnapshot.inspect(encoded)

        assertEquals(KwasmSnapshot.FORMAT_VERSION, header.formatVersion)
        assertContentEquals(sha256(module.encodedBytes()), header.moduleHash())
        assertEquals(
            GOLDEN_SUSPENDED_SNAPSHOT_SHA256,
            sha256(encoded).hex(),
            "the portable suspended-state fixture must encode identically on every target",
        )
        invocation.cancelAndJoin()

        val restoredStore = snapshotStore()
        val restored = KwasmSnapshot.restore(
            encoded,
            module,
            restoredStore,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) { emptyList() },
                ),
            ),
            hooks,
        )

        assertEquals(0xAB, restored.memories.single().loadByte(17))
        assertEquals(Value.Ref.Extern(77), restored.tables.single().get(0))
        assertEquals(Value.Ref.Extern(77), restored.globals[0].value)
        assertEquals(0x7FC0_0000, (restored.globals[1].value as Value.F32).v.toRawBits())
        assertEquals(StoreStatus.Paused, restoredStore.status.value)
        assertTrue(restoredStore.hasRestoredExecution)
        assertEquals(0, restoredStore.pendingImport?.functionIndex)

        // The format is deterministic for the same suspended state.
        assertContentEquals(encoded, KwasmSnapshot.capture(restored, hooks))
        assertEquals(emptyList(), restored.resume())
        assertEquals(StoreStatus.Idle, restoredStore.status.value)
        assertTrue(!restoredStore.hasRestoredExecution)
    }

    @Test
    fun throwingHostCommitPoisonsTargetBeforeRuntimeStateIsInstalled(): Unit = runBlocking {
        val module = Module.decode(WatComposer.compose(straightLineModuleWat()))
        val sourceStore = snapshotStore()
        val source = Instance(sourceStore, module, ResolvedImports())
        sourceStore.registerHostSnapshotParticipant(
            source,
            snapshotParticipant("test.transaction") {
                HostSnapshotRestore {}
            },
        )
        val pause = sourceStore.requestPause()
        val invocation = async { source.invoke("run") }
        pause.awaitPaused()
        val encoded = KwasmSnapshot.capture(source)
        invocation.cancelAndJoin()

        val targetStore = snapshotStore()
        val target = Instance(targetStore, module, ResolvedImports())
        targetStore.registerHostSnapshotParticipant(
            target,
            snapshotParticipant("test.transaction") {
                HostSnapshotRestore { error("broken participant commit") }
            },
        )

        assertFailsWith<IllegalStateException> {
            KwasmSnapshot.restore(encoded, target)
        }
        assertTrue(targetStore.poisoned)
        assertFalse(targetStore.hasRestoredExecution)
    }

    @Test
    fun initialEntryPauseResumesTheUnexecutedRootInstruction(): Unit = runBlocking {
        assertPauseSnapshotMatchesUninterrupted(
            wat = straightLineModuleWat(),
            checkpointInterval = 16_384,
            pauseAtCheckpoint = 1,
        )
    }

    @Test
    fun straightLinePauseResumesAtTheExactProgramCounter(): Unit = runBlocking {
        assertPauseSnapshotMatchesUninterrupted(
            wat = straightLineModuleWat(),
            checkpointInterval = 1,
            // entry, before i32.const 40, before i32.const 2
            pauseAtCheckpoint = 3,
            fuelEnabled = true,
            initialFuel = 100,
        )
    }

    @Test
    fun loopBackEdgePauseResumesAtTheLoopHeader(): Unit = runBlocking {
        assertPauseSnapshotMatchesUninterrupted(
            wat = loopModuleWat(),
            checkpointInterval = 16_384,
            // entry, then the first taken br_if back-edge
            pauseAtCheckpoint = 2,
        )
    }

    @Test
    fun callCheckpointPreservesArgumentsAndReexecutesTheCall(): Unit = runBlocking {
        assertPauseSnapshotMatchesUninterrupted(
            wat = callModuleWat(),
            checkpointInterval = 16_384,
            // entry, then the mandatory checkpoint immediately before call
            pauseAtCheckpoint = 2,
            fuelEnabled = true,
            initialFuel = 100,
        )
    }

    @Test
    fun fuelWaitResumesTheInstructionThatCouldNotPayItsCost(): Unit = runBlocking {
        val module = Module.decode(WatComposer.compose(straightLineModuleWat()))
        val baselineStore = Store(
            StoreConfig(
                canonicalizeNaNs = true,
                fuelEnabled = true,
                initialFuel = 100,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
            ),
        )
        val baseline = Instance(baselineStore, module, ResolvedImports())
        val baselineResult = baseline.invoke("run")

        val sourceStore = Store(
            StoreConfig(
                canonicalizeNaNs = true,
                fuelEnabled = true,
                initialFuel = 0,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
            ),
        )
        val source = Instance(sourceStore, module, ResolvedImports())
        coroutineScope {
            val invocation = async { source.invoke("run") }
            sourceStore.status.first { it == StoreStatus.WaitingForFuel }
            val encoded = KwasmSnapshot.capture(source)
            invocation.cancelAndJoin()

            val restoredStore = Store(
                StoreConfig(
                    canonicalizeNaNs = true,
                    fuelEnabled = true,
                    initialFuel = 0,
                    fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
                ),
            )
            val restored = KwasmSnapshot.restore(
                encoded,
                module,
                restoredStore,
                ResolvedImports(),
            )
            val resumed = async { restored.resume() }
            restoredStore.status.first { it == StoreStatus.WaitingForFuel }
            restoredStore.addFuel(100)

            assertEquals(baselineResult, resumed.await())
            assertEquals(
                baseline.globals.map { it.value },
                restored.globals.map { it.value },
            )
            assertEquals(baselineStore.fuel, restoredStore.fuel)
        }
    }

    @Test
    fun refusesModuleAndStoreConfigurationMismatches(): Unit = runBlocking {
        val module = Module.decode(suspendModuleBytes())
        val captured = capture(module)
        val semanticallySameButDifferentBytes = Module.decode(
            suspendModuleBytes() + byteArrayOf(0x00, 0x02, 0x01, 'x'.code.toByte()),
        )

        assertFailsWith<SnapshotModuleMismatch> {
            KwasmSnapshot.restore(
                captured,
                Instance(snapshotStore(), semanticallySameButDifferentBytes, noOpImports()),
                integerHooks(),
            )
        }
        assertFailsWith<SnapshotStateException> {
            KwasmSnapshot.restore(
                captured,
                Instance(
                    snapshotStore(maxFrames = 7),
                    module,
                    noOpImports(),
                ),
                integerHooks(),
            )
        }
    }

    @Test
    fun externRefsRequireExplicitPortableHooks(): Unit = runBlocking {
        val module = Module.decode(suspendModuleBytes())
        val release = CompletableDeferred<Unit>()
        val store = snapshotStore()
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        release.await()
                        emptyList()
                    },
                ),
            ),
        )
        instance.tables.single().set(0, Value.Ref.Extern(9))
        instance.globals[0].set(Value.Ref.Extern(9))
        val invocation = async { instance.invoke("run") }
        store.status.first { it == StoreStatus.InHostImport }

        val failure = assertFailsWith<SnapshotStateException> {
            KwasmSnapshot.capture(instance)
        }
        assertTrue(failure.message.orEmpty().contains("SnapshotHooks"))
        invocation.cancelAndJoin()
    }

    @Test
    fun hostileLengthsCountsAndTruncationFailBeforeAllocation(): Unit = runBlocking {
        val module = Module.decode(suspendModuleBytes())
        val encoded = capture(module)

        val badMagic = encoded.copyOf().also { it[0] = 0 }
        assertFailsWith<SnapshotFormatException> { KwasmSnapshot.inspect(badMagic) }

        val truncated = encoded.copyOf(encoded.size - 1)
        assertFailsWith<SnapshotFormatException> { KwasmSnapshot.inspect(truncated) }

        val hugeFirstSection = encoded.copyOf()
        val firstSectionLengthOffset = headerSize(hugeFirstSection) + 4
        repeat(8) { hugeFirstSection[firstSectionLengthOffset + it] = 0xFF.toByte() }
        assertFailsWith<SnapshotFormatException> { KwasmSnapshot.inspect(hugeFirstSection) }

        val hugeMemoryCount = encoded.copyOf()
        val memoryPayload = sectionPayloadOffset(hugeMemoryCount, 2)
        hugeMemoryCount[memoryPayload] = 0xFF.toByte()
        hugeMemoryCount[memoryPayload + 1] = 0xFF.toByte()
        hugeMemoryCount[memoryPayload + 2] = 0xFF.toByte()
        hugeMemoryCount[memoryPayload + 3] = 0x7F
        assertFailsWith<SnapshotFormatException> {
            KwasmSnapshot.restore(
                hugeMemoryCount,
                Instance(snapshotStore(), module, noOpImports()),
                integerHooks(),
            )
        }
    }

    @Test
    fun gcCyclesExceptionsAndArbitraryHostRefsRoundTrip(): Unit = runBlocking {
        val module = Module.decode(graphModuleBytes())
        val sourceStore = snapshotStore()
        val source = Instance(sourceStore, module, ResolvedImports())
        val structFields = mutableListOf<Value>()
        val arrayElements = mutableListOf<Value>()
        val struct = StructObject(source, 0, structFields)
        val array = ArrayObject(source, 1, arrayElements)
        structFields += Value.Ref.Gc(array)
        arrayElements += Value.Ref.Gc(struct)
        val exception = GuestException(
            source.tags.single(),
            listOf(Value.Ref.Gc(struct)),
            tagIndex = 0,
        )
        val sourceHost = HostToken("source")
        val restoredHost = HostToken("restored")
        var externalizeCalls = 0
        var rehydrateCalls = 0
        val hooks = object : SnapshotHooks {
            override fun externalizeExternRef(handle: Int): ByteArray? = null
            override fun rehydrateExternRef(key: ByteArray): Int? = null

            override fun externalizeHostExternRef(value: Any): ByteArray? {
                externalizeCalls++
                return if (value === sourceHost || value === restoredHost) {
                    byteArrayOf(0x47)
                } else {
                    null
                }
            }

            override fun rehydrateHostExternRef(key: ByteArray): Any? {
                rehydrateCalls++
                return if (key.contentEquals(byteArrayOf(0x47))) restoredHost else null
            }
        }
        val state = graphState(
            source,
            listOf(
                Value.Ref.Gc(struct),
                Value.Ref.Gc(array),
                Value.Ref.Exn(exception),
                Value.Ref.Host(sourceHost),
                Value.Ref.AnyExtern(Value.Ref.Host(sourceHost)),
            ),
        )

        val encoded = SnapshotBinary.encode(
            source,
            sourceStore,
            state,
            hooks,
            SnapshotLimits(),
            preCapturedHostStates = emptyList(),
        )
        assertEquals(1, externalizeCalls, "a repeated host identity is externalized once")
        assertFailsWith<SnapshotStateException> {
            SnapshotBinary.encode(
                source,
                sourceStore,
                state,
                hooks,
                SnapshotLimits(maxHeapObjects = 2),
                preCapturedHostStates = emptyList(),
            )
        }
        assertFailsWith<SnapshotStateException> {
            SnapshotBinary.encode(
                source,
                sourceStore,
                state,
                hooks,
                SnapshotLimits(maxHeapValues = 2),
                preCapturedHostStates = emptyList(),
            )
        }
        assertFailsWith<SnapshotStateException> {
            SnapshotBinary.encode(
                source,
                sourceStore,
                state,
                hooks,
                SnapshotLimits(maxReferenceNesting = 0),
                preCapturedHostStates = emptyList(),
            )
        }
        assertFailsWith<SnapshotFormatException> {
            SnapshotBinary.decode(
                encoded,
                hooks,
                SnapshotLimits(maxHeapObjects = 2),
            )
        }
        assertFailsWith<SnapshotFormatException> {
            SnapshotBinary.decode(
                encoded,
                hooks,
                SnapshotLimits(maxReferenceNesting = 0),
            )
        }

        val targetStore = snapshotStore()
        val target = Instance(targetStore, module, ResolvedImports())
        val rehydrateCallsBeforeRestore = rehydrateCalls
        KwasmSnapshot.restore(encoded, target, hooks)
        assertEquals(
            rehydrateCallsBeforeRestore + 1,
            rehydrateCalls,
            "a repeated host key is rehydrated once per decode",
        )

        val restoredStruct = (target.globals[0].value as Value.Ref.Gc).value as StructObject
        val restoredArray = (target.globals[1].value as Value.Ref.Gc).value as ArrayObject
        val restoredException =
            (target.globals[2].value as Value.Ref.Exn).value ?: error("missing exception")
        val rawHost = (target.globals[3].value as Value.Ref.Host).value
        val convertedHost =
            ((target.globals[4].value as Value.Ref.AnyExtern).external as Value.Ref.Host).value

        assertSame(target, restoredStruct.owner)
        assertSame(target, restoredArray.owner)
        assertSame(restoredArray, (restoredStruct.fields.single() as Value.Ref.Gc).value)
        assertSame(restoredStruct, (restoredArray.elements.single() as Value.Ref.Gc).value)
        assertSame(target.tags.single(), restoredException.tag)
        assertEquals(0, restoredException.tagIndex)
        assertSame(
            restoredStruct,
            (restoredException.arguments.single() as Value.Ref.Gc).value,
        )
        assertSame(restoredHost, rawHost)
        assertSame(restoredHost, convertedHost)
        assertContentEquals(
            encoded,
            KwasmSnapshot.capture(target, hooks),
            "the restored cyclic graph re-encodes deterministically",
        )
    }

    @Test
    fun arbitraryHostRefsNeedPortableHooks(): Unit = runBlocking {
        val module = Module.decode(graphModuleBytes())
        val sourceStore = snapshotStore()
        val source = Instance(sourceStore, module, ResolvedImports())
        val state = graphState(
            source,
            listOf(
                Value.NULL_GC,
                Value.NULL_GC,
                Value.NULL_EXN,
                Value.Ref.Host(HostToken("unhandled")),
                Value.NULL_GC,
            ),
        )

        val failure = assertFailsWith<SnapshotStateException> {
            SnapshotBinary.encode(
                source,
                sourceStore,
                state,
                null,
                SnapshotLimits(),
                preCapturedHostStates = emptyList(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("global 3"))
        assertTrue(failure.message.orEmpty().contains("SnapshotHooks"))
    }

    @Test
    fun caughtLegacyExceptionSurvivesSuspendRestoreAndRethrow(): Unit = runBlocking {
        val module = Module.decode(suspendInCatchModuleBytes())
        val release = CompletableDeferred<Unit>()
        val sourceStore = snapshotStore()
        val source = Instance(
            sourceStore,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        release.await()
                        emptyList()
                    },
                ),
            ),
        )
        val invocation = async { source.invoke("run") }
        sourceStore.status.first { it == StoreStatus.InHostImport }
        val encoded = KwasmSnapshot.capture(source)
        invocation.cancelAndJoin()

        val targetStore = snapshotStore()
        val target = KwasmSnapshot.restore(
            encoded,
            module,
            targetStore,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) { emptyList() },
                ),
            ),
        )
        val failure = try {
            target.resume()
            error("expected the restored rethrow to escape")
        } catch (failure: UncaughtWasmException) {
            failure
        }
        assertSame(target.tags.single(), failure.exception.tag)
        assertEquals(0, failure.exception.tagIndex)
        assertEquals(listOf(Value.I32(42)), failure.exception.arguments)
    }

    @Test
    fun sha256MatchesPublishedTestVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).hex(),
        )
    }

    private suspend fun assertPauseSnapshotMatchesUninterrupted(
        wat: String,
        checkpointInterval: Int,
        pauseAtCheckpoint: Int,
        fuelEnabled: Boolean = false,
        initialFuel: Long = 0,
    ): Unit = coroutineScope {
        val module = Module.decode(WatComposer.compose(wat))
        val baselineStore = Store(
            StoreConfig(
                canonicalizeNaNs = true,
                checkpointInterval = checkpointInterval,
                fuelEnabled = fuelEnabled,
                initialFuel = initialFuel,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
            ),
        )
        val baseline = Instance(baselineStore, module, ResolvedImports())
        val baselineResult = baseline.invoke("run")

        val pauseListener = PauseAtCheckpoint(pauseAtCheckpoint)
        val sourceStore = Store(
            StoreConfig(
                canonicalizeNaNs = true,
                checkpointInterval = checkpointInterval,
                fuelEnabled = fuelEnabled,
                initialFuel = initialFuel,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
                listener = pauseListener,
            ),
        )
        val source = Instance(sourceStore, module, ResolvedImports())
        val invocation = async { source.invoke("run") }
        val pause = pauseListener.pause.await()
        pause.awaitPaused()
        assertEquals(StoreStatus.Paused, sourceStore.status.value)
        val encoded = KwasmSnapshot.capture(source)
        invocation.cancelAndJoin()

        val restoredStore = Store(
            StoreConfig(
                canonicalizeNaNs = true,
                checkpointInterval = checkpointInterval,
                fuelEnabled = fuelEnabled,
                initialFuel = initialFuel,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
            ),
        )
        val restored = KwasmSnapshot.restore(
            encoded,
            module,
            restoredStore,
            ResolvedImports(),
        )

        assertEquals(baselineResult, restored.resume())
        assertEquals(
            baseline.globals.map { it.value },
            restored.globals.map { it.value },
        )
        assertEquals(baselineStore.fuel, restoredStore.fuel)
    }

    private suspend fun capture(module: Module): ByteArray {
        val release = CompletableDeferred<Unit>()
        val store = snapshotStore()
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        release.await()
                        emptyList()
                    },
                ),
            ),
        )
        val invocation = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.currentCoroutineContext(),
        ).async { instance.invoke("run") }
        store.status.first { it == StoreStatus.InHostImport }
        val encoded = KwasmSnapshot.capture(instance, integerHooks())
        invocation.cancelAndJoin()
        return encoded
    }

    private class PauseAtCheckpoint(
        private val target: Int,
    ) : ExecutionListener {
        val pause: CompletableDeferred<PauseHandle> = CompletableDeferred()
        private var seen: Int = 0

        override fun onCheckpoint(
            store: Store,
            functionIndex: Int?,
            instructionIndex: Int?,
        ) {
            seen++
            if (seen == target) {
                pause.complete(store.requestPause())
            }
        }
    }

    private fun straightLineModuleWat(): String =
        """
        (module
          (global ${'$'}state (mut i32) (export "state") (i32.const 0))
          (func (export "run") (result i32)
            i32.const 40
            i32.const 2
            i32.add
            global.set ${'$'}state
            global.get ${'$'}state))
        """.trimIndent()

    private fun callModuleWat(): String =
        """
        (module
          (global ${'$'}state (mut i32) (export "state") (i32.const 0))
          (func ${'$'}increment (param i32) (result i32)
            local.get 0
            i32.const 1
            i32.add)
          (func (export "run") (result i32)
            i32.const 41
            call ${'$'}increment
            global.set ${'$'}state
            global.get ${'$'}state))
        """.trimIndent()

    private fun loopModuleWat(): String =
        """
        (module
          (global ${'$'}state (mut i32) (export "state") (i32.const 0))
          (func (export "run") (result i32)
            (loop
              global.get ${'$'}state
              i32.const 1
              i32.add
              global.set ${'$'}state
              global.get ${'$'}state
              i32.const 3
              i32.lt_s
              br_if 0)
            global.get ${'$'}state))
        """.trimIndent()

    private fun snapshotStore(maxFrames: Int = 65_536): Store =
        Store(
            StoreConfig(
                limits = ExecutionLimits(maxFrames = maxFrames),
                canonicalizeNaNs = true,
            ),
        )

    private fun noOpImports(): ResolvedImports =
        ResolvedImports(
            functions = listOf(
                HostImport(FuncType(emptyList(), emptyList())) { emptyList() },
            ),
        )

    private fun graphState(instance: Instance, globals: List<Value>): RuntimeStoreSnapshot =
        RuntimeStoreSnapshot(
            instance = RuntimeInstanceSnapshot(
                memories = emptyList(),
                tables = emptyList(),
                globals = globals,
                elementSegmentsDropped = emptyList(),
                dataSegments = emptyList(),
            ),
            valueStack = emptyList(),
            frames = emptyList(),
            pendingImport = null,
            fuel = 0,
            instructionsUntilCheckpoint = 0,
        )

    private fun integerHooks(): SnapshotHooks = object : SnapshotHooks {
        override fun externalizeExternRef(handle: Int): ByteArray =
            byteArrayOf(
                handle.toByte(),
                (handle ushr 8).toByte(),
                (handle ushr 16).toByte(),
                (handle ushr 24).toByte(),
            )

        override fun rehydrateExternRef(key: ByteArray): Int {
            if (key.size != 4) return -1
            return (key[0].toInt() and 0xFF) or
                ((key[1].toInt() and 0xFF) shl 8) or
                ((key[2].toInt() and 0xFF) shl 16) or
                ((key[3].toInt() and 0xFF) shl 24)
        }
    }

    private fun headerSize(bytes: ByteArray): Int {
        val versionLength = littleU16(bytes, 18)
        return 8 + 2 + 8 + 2 + versionLength + 32 + 2
    }

    private fun sectionPayloadOffset(bytes: ByteArray, wantedId: Int): Int {
        var position = headerSize(bytes)
        val sectionCount = littleU16(bytes, position - 2)
        repeat(sectionCount) {
            val id = littleU16(bytes, position)
            val length = littleU64(bytes, position + 4).toInt()
            val payload = position + 12
            if (id == wantedId) return payload
            position = payload + length
        }
        error("missing section $wantedId")
    }

    private fun littleU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun littleU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        return value
    }

    private fun ByteArray.hex(): String {
        val alphabet = "0123456789abcdef"
        return buildString(size * 2) {
            this@hex.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(alphabet[value ushr 4])
                append(alphabet[value and 15])
            }
        }
    }

    private fun suspendModuleBytes(): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // type: () -> ()
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // import host.pause as function 0
        0x02, 0x0E, 0x01,
        0x04, 0x68, 0x6F, 0x73, 0x74,
        0x05, 0x70, 0x61, 0x75, 0x73, 0x65,
        0x00, 0x00,
        // one local function of type 0
        0x03, 0x02, 0x01, 0x00,
        // externref table, minimum 1
        0x04, 0x04, 0x01, 0x6F, 0x00, 0x01,
        // memory, minimum 1 page
        0x05, 0x03, 0x01, 0x00, 0x01,
        // mutable externref global and mutable f32 global with a non-canonical NaN
        0x06, 0x0E, 0x02,
        0x6F, 0x01, 0xD0.toByte(), 0x6F, 0x0B,
        0x7D, 0x01, 0x43, 0x45, 0x23, 0xA1.toByte(), 0x7F, 0x0B,
        // export local function (absolute index 1) as run
        0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x01,
        // body: call import 0
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x10, 0x00, 0x0B,
    )

    private fun snapshotParticipant(
        id: String,
        prepare: (ByteArray) -> HostSnapshotRestore,
    ): InstanceScopedHostSnapshotParticipant =
        object : InstanceScopedHostSnapshotParticipant {
            override val id: String = id

            override fun capture(
                instance: Instance,
                hooks: HostSnapshotHooks?,
            ): ByteArray = byteArrayOf(1, 2, 3)

            override fun prepareRestore(
                payload: ByteArray,
                instance: Instance,
                hooks: HostSnapshotHooks?,
            ): HostSnapshotRestore = prepare(payload)
        }

    private companion object {
        const val GOLDEN_SUSPENDED_SNAPSHOT_SHA256: String =
            "09d1c73b9c5fda0dffcc147611fab8428808cf95a9d4cf32e8ea80f3be9c9b34"
    }

    /**
     * Types:
     *   rec { struct (field (mut arrayref))
     *         array  (field (mut structref)) }
     *   (func (param anyref))
     * One tag of the function type and mutable globals for both GC types,
     * exnref, externref, and anyref.
     */
    private fun graphModuleBytes(): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // type section: one two-member recursive group plus the tag function type
        0x01, 0x0E, 0x02,
        0x4E, 0x02,
        0x5F, 0x01, 0x6A, 0x01,
        0x5E, 0x6B, 0x01,
        0x60, 0x01, 0x6E, 0x00,
        // tag section: tag 0 has type 2
        0x0D, 0x03, 0x01, 0x00, 0x02,
        // five mutable reference globals, all initialized to null
        0x06, 0x1A, 0x05,
        0x6B, 0x01, 0xD0.toByte(), 0x6B, 0x0B,
        0x6A, 0x01, 0xD0.toByte(), 0x6A, 0x0B,
        0x69, 0x01, 0xD0.toByte(), 0x69, 0x0B,
        0x6F, 0x01, 0xD0.toByte(), 0x6F, 0x0B,
        0x6E, 0x01, 0xD0.toByte(), 0x6E, 0x0B,
    )

    /** Throw, enter a legacy catch, suspend in a host call, then rethrow. */
    private fun suspendInCatchModuleBytes(): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // types: (i32)->(), ()->(), ()->()
        0x01, 0x0B, 0x03,
        0x60, 0x01, 0x7F, 0x00,
        0x60, 0x00, 0x00,
        0x60, 0x00, 0x00,
        // import host.pause as function 0, type 1
        0x02, 0x0E, 0x01,
        0x04, 0x68, 0x6F, 0x73, 0x74,
        0x05, 0x70, 0x61, 0x75, 0x73, 0x65,
        0x00, 0x01,
        // local function type 2
        0x03, 0x02, 0x01, 0x02,
        // one exception tag, type 0
        0x0D, 0x03, 0x01, 0x00, 0x00,
        // export local function 1 as run
        0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x01,
        // try { i32.const 42; throw 0 } catch 0 { call 0; rethrow 0 }
        0x0A, 0x11, 0x01, 0x0F, 0x00,
        0x06, 0x40,
        0x41, 0x2A,
        0x08, 0x00,
        0x07, 0x00,
        0x10, 0x00,
        0x09, 0x00,
        0x0B,
        0x0B,
    )

    private data class HostToken(val label: String)
}
