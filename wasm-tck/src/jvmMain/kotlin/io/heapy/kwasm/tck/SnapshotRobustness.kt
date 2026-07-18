package io.heapy.kwasm.tck

import io.heapy.kwasm.ExecutionLimits
import io.heapy.kwasm.ExecutionListener
import io.heapy.kwasm.FuelExhaustionPolicy
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.PauseHandle
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.SnapshotException
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.Value
import io.heapy.kwasm.snapshot.KwasmSnapshot
import io.heapy.kwasm.snapshot.SnapshotLimits
import io.heapy.kwasm.wat.WatComposer
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

internal data class SnapshotRobustnessReport(
    val seed: ULong,
    val propertyChecks: Int,
    val mutationIterations: Int,
    val inspectRejections: Int,
    val restoreRejections: Int,
    val acceptedMutations: Int,
) {
    fun describe(): String =
        "kwasm snapshot robustness: seed=$seed, propertyChecks=$propertyChecks, " +
            "mutations=$mutationIterations, inspectRejected=$inspectRejections, " +
            "restoreRejected=$restoreRejections, acceptedMutations=$acceptedMutations"
}

/**
 * JVM-only snapshot gate using the real codec and real suspended interpreter
 * frames. All inputs remain local and all decode/restore allocations are
 * bounded by [strictSnapshotLimits].
 */
internal class SnapshotRobustnessDriver(
    private val configuration: NightlyFuzzConfiguration,
    private val evidence: FuzzEvidenceStore,
) {
    private val limits: SnapshotLimits = strictSnapshotLimits()
    private val moduleBytes: ByteArray = WatComposer.compose(snapshotPropertyWat())
    private val module: Module = Module.decode(moduleBytes, configuration.validationLimits)

    suspend fun run(): SnapshotRobustnessReport {
        val random = SplitMix64(configuration.snapshotSeed)
        var seedSnapshot: ByteArray? = null
        repeat(configuration.snapshotPropertyIterations) { caseIndex ->
            val loopIterations = 24 + (random.nextULong() % 40u).toInt()
            // Entry and per-instruction checkpoints make these low values
            // guaranteed to occur before this loop can finish.
            val stepsBeforeCapture = 1 + (random.nextULong() % 16u).toInt()
            try {
                val captured = runContinuationProperty(loopIterations, stepsBeforeCapture)
                if (seedSnapshot == null) seedSnapshot = captured
            } catch (failure: Exception) {
                evidence.writeFinding(
                    kind = "snapshot-continuation",
                    case = caseIndex,
                    extension = "wasm",
                    input = moduleBytes,
                    metadata = linkedMapOf(
                        "seed" to configuration.snapshotSeed.toString(),
                        "case" to caseIndex.toString(),
                        "loopIterations" to loopIterations.toString(),
                        "stepsBeforeCapture" to stepsBeforeCapture.toString(),
                        "failure" to stableSnapshotFailure(failure),
                    ),
                )
                throw failure
            }
        }

        if (configuration.snapshotMutationIterations > 0 && seedSnapshot == null) {
            seedSnapshot = captureSeedSnapshot()
        }
        var inspectRejections = 0
        var restoreRejections = 0
        var accepted = 0
        val original = seedSnapshot
        if (original != null) {
            repeat(configuration.snapshotMutationIterations) { caseIndex ->
                val mutation = mutateSnapshot(original, random)
                try {
                    try {
                        KwasmSnapshot.inspect(mutation.bytes, limits)
                    } catch (_: SnapshotException) {
                        inspectRejections++
                        return@repeat
                    }

                    val target = Instance(snapshotStore(), module, ResolvedImports())
                    try {
                        KwasmSnapshot.restore(
                            bytes = mutation.bytes,
                            instance = target,
                            limits = limits,
                        )
                    } catch (_: SnapshotException) {
                        restoreRejections++
                        return@repeat
                    }

                    // A mutation may still encode a valid alternate suspended
                    // state. Re-encoding it exercises graph traversal and all
                    // encode limits without assuming every mutation is invalid.
                    val roundTripped = KwasmSnapshot.capture(target, limits = limits)
                    KwasmSnapshot.inspect(roundTripped, limits)
                    accepted++
                } catch (failure: Exception) {
                    evidence.writeFinding(
                        kind = "snapshot-mutation",
                        case = caseIndex,
                        extension = "kwsnap",
                        input = mutation.bytes,
                        metadata = linkedMapOf(
                            "seed" to configuration.snapshotSeed.toString(),
                            "case" to caseIndex.toString(),
                            "operation" to mutation.operation,
                            "sourceBytes" to original.size.toString(),
                            "mutatedBytes" to mutation.bytes.size.toString(),
                            "failure" to stableSnapshotFailure(failure),
                        ),
                    )
                    throw failure
                }
            }
        }
        return SnapshotRobustnessReport(
            seed = configuration.snapshotSeed,
            propertyChecks = configuration.snapshotPropertyIterations,
            mutationIterations = configuration.snapshotMutationIterations,
            inspectRejections = inspectRejections,
            restoreRejections = restoreRejections,
            acceptedMutations = accepted,
        )
    }

    /**
     * Run to completion once, then repeat with a pause after exactly [steps]
     * cooperative checkpoints, capture, restore into a fresh Store, and finish.
     */
    private suspend fun runContinuationProperty(
        loopIterations: Int,
        steps: Int,
    ): ByteArray {
        val baselineStore = snapshotStore()
        val baseline = Instance(baselineStore, module, ResolvedImports())
        val baselineResult = withTimeout(configuration.processTimeoutMillis) {
            baseline.invoke("run", listOf(Value.I32(loopIterations)))
        }
        val baselineObservation = observe(baseline, baselineResult)

        val listener = PauseAfterCheckpoints(steps)
        val sourceStore = snapshotStore(listener)
        val source = Instance(sourceStore, module, ResolvedImports())
        val captured = coroutineScope {
            val invocation = async {
                source.invoke("run", listOf(Value.I32(loopIterations)))
            }
            try {
                val pause = withTimeout(configuration.processTimeoutMillis) {
                    listener.pause.await()
                }
                withTimeout(configuration.processTimeoutMillis) {
                    pause.awaitPaused()
                }
                KwasmSnapshot.capture(source, limits = limits)
            } finally {
                invocation.cancelAndJoin()
            }
        }

        val restoredStore = snapshotStore()
        val restored = KwasmSnapshot.restore(
            bytes = captured,
            module = module,
            store = restoredStore,
            imports = ResolvedImports(),
            limits = limits,
        )
        val restoredResult = withTimeout(configuration.processTimeoutMillis) {
            restored.resume()
        }
        val restoredObservation = observe(restored, restoredResult)
        check(baselineObservation == restoredObservation) {
            "snapshot continuation diverged after $steps checkpoint(s): " +
                "baseline=$baselineObservation restored=$restoredObservation"
        }
        return captured
    }

    private suspend fun captureSeedSnapshot(): ByteArray {
        val listener = PauseAfterCheckpoints(4)
        val store = snapshotStore(listener)
        val instance = Instance(store, module, ResolvedImports())
        return coroutineScope {
            val invocation = async {
                instance.invoke("run", listOf(Value.I32(32)))
            }
            try {
                val pause = withTimeout(configuration.processTimeoutMillis) {
                    listener.pause.await()
                }
                withTimeout(configuration.processTimeoutMillis) {
                    pause.awaitPaused()
                }
                KwasmSnapshot.capture(instance, limits = limits)
            } finally {
                invocation.cancelAndJoin()
            }
        }
    }

    private fun snapshotStore(listener: ExecutionListener? = null): Store =
        Store(
            StoreConfig(
                limits = ExecutionLimits(
                    maxFrames = 128,
                    maxValueStackSlots = 4_096,
                ),
                checkpointInterval = 1,
                canonicalizeNaNs = true,
                fuelEnabled = true,
                initialFuel = configuration.executionFuel,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Trap,
                listener = listener,
            ),
        )

    private fun observe(instance: Instance, result: List<Value>): SnapshotObservation =
        SnapshotObservation(
            results = result.map(::normalizeKwasmValue),
            globals = instance.globals.map { normalizeKwasmValue(it.value) },
            memorySha256 = instance.memories.map { sha256(it.data()) },
            remainingFuel = instance.store.fuel,
        )

    private data class SnapshotObservation(
        val results: List<String>,
        val globals: List<String>,
        val memorySha256: List<String>,
        val remainingFuel: Long,
    )

    private class PauseAfterCheckpoints(
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
            if (seen == target && !pause.isCompleted) {
                pause.complete(store.requestPause())
            }
        }
    }

    private data class SnapshotMutation(
        val operation: String,
        val bytes: ByteArray,
    )

    private fun mutateSnapshot(
        source: ByteArray,
        random: SplitMix64,
    ): SnapshotMutation =
        when ((random.nextULong() % 5u).toInt()) {
            0 -> {
                val mutated = source.copyOf()
                val changes = 1 + (random.nextULong() % 8u).toInt()
                repeat(changes) {
                    val index = (random.nextULong() % mutated.size.toULong()).toInt()
                    val bit = 1 shl (random.nextULong() % 8u).toInt()
                    mutated[index] = (mutated[index].toInt() xor bit).toByte()
                }
                SnapshotMutation("bit-flip:$changes", mutated)
            }
            1 -> {
                val newSize = (random.nextULong() % source.size.toULong()).toInt()
                SnapshotMutation("truncate:$newSize", source.copyOf(newSize))
            }
            2 -> {
                val start = (random.nextULong() % source.size.toULong()).toInt()
                val maximum = minOf(256, source.size - start)
                val length = 1 + (random.nextULong() % maximum.toULong()).toInt()
                SnapshotMutation(
                    "delete:$start:$length",
                    source.copyOfRange(0, start) + source.copyOfRange(start + length, source.size),
                )
            }
            3 -> {
                val start = (random.nextULong() % source.size.toULong()).toInt()
                val maximum = minOf(256, source.size - start)
                val length = 1 + (random.nextULong() % maximum.toULong()).toInt()
                val mutated = source.copyOf()
                repeat(length) { offset ->
                    mutated[start + offset] = random.nextULong().toByte()
                }
                SnapshotMutation("overwrite:$start:$length", mutated)
            }
            else -> {
                val append = 1 + (random.nextULong() % 256u).toInt()
                val mutated = source + ByteArray(append) { random.nextULong().toByte() }
                SnapshotMutation("append:$append", mutated)
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}

internal fun strictSnapshotLimits(): SnapshotLimits =
    SnapshotLimits(
        maxSnapshotBytes = 256 * 1024,
        maxSectionBytes = 128 * 1024,
        maxSections = 16,
        maxMemories = 2,
        maxTotalMemoryBytes = 128L * 1024L,
        maxTables = 2,
        maxTableElements = 1_024,
        maxGlobals = 32,
        maxValues = 4_096,
        maxFrames = 128,
        maxControlsPerFrame = 128,
        maxLocalsPerFrame = 256,
        maxBodyPathDepth = 64,
        maxSegments = 128,
        maxBlobBytes = 128 * 1024,
        maxExternKeyBytes = 1_024,
        maxHostParticipants = 8,
        maxHostParticipantIdBytes = 128,
        maxHostParticipantStateBytes = 64 * 1024,
        maxRuntimeVersionBytes = 64,
        maxHeapObjects = 256,
        maxHeapValues = 4_096,
        maxReferenceNesting = 32,
    )

private fun snapshotPropertyWat(): String =
    """
    (module
      (memory (export "memory") 1 1)
      (global ${'$'}state (mut i64) (i64.const 0))
      (func (export "run") (param ${'$'}iterations i32) (result i64)
        (local ${'$'}index i32)
        (block ${'$'}done
          (loop ${'$'}again
            local.get ${'$'}index
            local.get ${'$'}iterations
            i32.ge_u
            br_if ${'$'}done

            global.get ${'$'}state
            i64.const 6364136223846793005
            i64.mul
            local.get ${'$'}index
            i64.extend_i32_u
            i64.add
            i64.const 1442695040888963407
            i64.add
            global.set ${'$'}state

            i32.const 0
            global.get ${'$'}state
            i64.store

            local.get ${'$'}index
            i32.const 1
            i32.add
            local.tee ${'$'}index
            local.get ${'$'}iterations
            i32.lt_u
            br_if ${'$'}again))
        global.get ${'$'}state))
    """.trimIndent()

private fun stableSnapshotFailure(failure: Throwable): String =
    "${failure::class.qualifiedName}: ${failure.message.orEmpty()}".take(2_048)
