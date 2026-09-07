package io.heapy.kwasm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Policy used when an enabled fuel counter cannot pay for the next instruction. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class FuelExhaustionPolicy {
    Trap,
    Suspend,
}

/**
 * Optional instruction-cost override. The default cost is one for every
 * instruction.
 *
 * A cost table cannot be expressed by the checkpoint countdown, so supplying
 * one selects the per-instruction metered interpreter loop. Leaving it unset
 * keeps fuel on the checkpoint-budget path.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface InstructionCostTable {
    public fun cost(instruction: Instr): Long
}

/**
 * Selects whether cooperative checkpoint code participates in execution.
 *
 * [CompiledOutEquivalent] exists only to measure the `SUSP-5` cost. It
 * selects a separate interpreter loop without the per-instruction checkpoint
 * countdown and suppresses call-entry and loop-back-edge checkpoints. It
 * therefore disables cancellation observation, pausing, snapshots, and fuel;
 * embedders must never use it for untrusted or production guests.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class CheckpointMode {
    Enabled,

    @io.heapy.kwasm.InternalKwasmApi
    CompiledOutEquivalent,
}

/** Guest-owned execution ceilings. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class ExecutionLimits(
    public val maxFrames: Int = 65_536,
    public val maxValueStackSlots: Int = 1_048_576,
) {
    init {
        require(maxFrames > 0) { "maxFrames must be positive" }
        require(maxValueStackSlots > 0) { "maxValueStackSlots must be positive" }
    }
}

/** Store-level execution configuration. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class StoreConfig(
    public val limits: ExecutionLimits = ExecutionLimits(),
    public val checkpointInterval: Int = 16_384,
    public val checkpointMode: CheckpointMode = CheckpointMode.Enabled,
    public val canonicalizeNaNs: Boolean = false,
    public val fuelEnabled: Boolean = false,
    public val initialFuel: Long = 0,
    public val fuelExhaustionPolicy: FuelExhaustionPolicy = FuelExhaustionPolicy.Trap,
    public val instructionCosts: InstructionCostTable? = null,
    public val listener: ExecutionListener? = null,
) {
    init {
        require(checkpointInterval > 0) { "checkpointInterval must be positive" }
        require(initialFuel >= 0) { "initialFuel must not be negative" }
        require(checkpointMode == CheckpointMode.Enabled || !fuelEnabled) {
            "fuel requires checkpointMode=Enabled"
        }
    }
}

/**
 * Coarse store state suitable for monitoring and snapshot coordination.
 *
 * Suspension statuses are published from inside the store's gated execution
 * segments: [InHostImport], [Paused], and [WaitingForFuel] become externally
 * observable before the executing continuation actually parks and releases
 * the execution gate. A cross-thread observer that reacts to [Store.status]
 * alone can therefore call [Store.captureSnapshotState] while the segment is
 * still running and fail with "store execution has not parked". Await
 * [Store.awaitSnapshotCapturable] instead when the observation is meant to
 * precede a snapshot capture.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class StoreStatus {
    Idle,
    Running,
    Paused,
    WaitingForFuel,
    InHostImport,
    Poisoned,
}

/** Optional low-overhead execution observer. */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface ExecutionListener {
    public fun onCallStarted(instance: Instance, functionIndex: Int, arguments: List<Value>) {}
    public fun onCheckpoint(store: Store, functionIndex: Int?, instructionIndex: Int?) {}
    public fun onCallFinished(instance: Instance, functionIndex: Int, results: List<Value>) {}
    public fun onTrap(instance: Instance, trap: WasmTrap) {}
}

/**
 * Thread-safe control surface for a coroutine-confined [Store].
 *
 * Refuelling and pause requests may originate on another dispatcher. All guest
 * state remains confined to the coroutine currently executing the store.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class StoreController internal constructor(initialFuel: Long) {
    private val fuelState = MutableStateFlow(initialFuel)
    private val requestedPause = MutableStateFlow(0L)
    private val observedPause = MutableStateFlow(0L)
    private val resumedPause = MutableStateFlow(0L)
    @Volatile
    internal var pausePending: Boolean = false
        private set

    /**
     * Fuel remaining as of the last settlement.
     *
     * Under the default cost table the interpreter reserves a slice of fuel per
     * checkpoint and settles the burn when that slice ends, so this value is
     * published at checkpoint granularity rather than per instruction. It is
     * settled whenever execution parks or finishes. [Store.fuel] additionally
     * subtracts the burn of a slice that is still in flight.
     */
    public val fuel: StateFlow<Long> = fuelState.asStateFlow()

    public fun addFuel(amount: Long) {
        require(amount > 0) { "fuel amount must be positive" }
        fuelState.update { current ->
            if (Long.MAX_VALUE - current < amount) Long.MAX_VALUE else current + amount
        }
    }

    public fun requestPause(): PauseHandle {
        val generation = increment(requestedPause)
        pausePending = true
        return PauseHandle(generation, observedPause, this)
    }

    internal fun tryConsumeFuel(cost: Long): Boolean {
        require(cost >= 0) { "instruction fuel cost must not be negative" }
        if (cost == 0L) return true
        while (true) {
            val current = fuelState.value
            if (current < cost) return false
            if (fuelState.compareAndSet(current, current - cost)) return true
        }
    }

    internal fun canPayFuel(cost: Long): Boolean {
        require(cost >= 0) { "instruction fuel cost must not be negative" }
        return fuelState.value >= cost
    }

    internal fun fuelValue(): Long = fuelState.value

    /** Settles a burn that was metered by the checkpoint countdown. */
    internal fun consumeFuel(amount: Long) {
        if (amount <= 0) return
        while (true) {
            val current = fuelState.value
            val next = if (current <= amount) 0L else current - amount
            if (fuelState.compareAndSet(current, next)) return
        }
    }

    internal suspend fun awaitFuel(minimum: Long) {
        fuelState.first { it >= minimum }
    }

    internal fun hasPauseRequest(): Boolean = pausePending

    internal suspend fun awaitResume() {
        val generation = requestedPause.value
        if (generation <= resumedPause.value) return
        observedPause.value = generation
        resumedPause.first { it >= generation }
    }

    internal fun resumePause(generation: Long) {
        while (true) {
            val current = resumedPause.value
            if (
                current >= generation ||
                resumedPause.compareAndSet(current, generation)
            ) {
                if (resumedPause.value >= requestedPause.value) {
                    pausePending = false
                    // A concurrent request can publish its generation before
                    // setting the fast hint. Recheck after clearing so an
                    // older resume cannot erase that newer request.
                    if (resumedPause.value < requestedPause.value) {
                        pausePending = true
                    }
                }
                return
            }
        }
    }

    internal fun restoreFuel(value: Long) {
        require(value >= 0) { "restored fuel must not be negative" }
        fuelState.value = value
    }

    private fun increment(flow: MutableStateFlow<Long>): Long {
        while (true) {
            val current = flow.value
            val next = if (current == Long.MAX_VALUE) 1L else current + 1L
            if (flow.compareAndSet(current, next)) return next
        }
    }
}

/** Handle for one explicit pause request. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class PauseHandle internal constructor(
    private val generation: Long,
    private val observedPause: StateFlow<Long>,
    private val controller: StoreController,
) {
    public suspend fun awaitPaused() {
        observedPause.first { it >= generation }
    }

    public fun resume(): Unit = controller.resumePause(generation)
}

/** Description of a host call while its suspend function is parked. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class PendingImport(
    public val functionIndex: Int,
    public val arguments: List<Value>,
)

/**
 * Owner of all mutable runtime and execution state.
 *
 * A store is deliberately not re-entrant or thread-safe. Only [controller] is
 * safe to call externally while the owner coroutine is suspended.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class Store(
    public val config: StoreConfig = StoreConfig(),
    parentContext: CoroutineContext = EmptyCoroutineContext,
    /**
     * Execution engine for every guest call made against this store. Owning it
     * here keeps the engine and the state it mutates on one object instead of
     * letting each call site pick its own.
     */
    public val machine: ResumableMachine = Interpreter(),
) {
    /** Lifetime job for executions launched through [scope]. */
    public val job: Job = SupervisorJob(parentContext[Job])
    /** Structured-concurrency scope owned by this store. */
    public val scope: CoroutineScope = CoroutineScope(parentContext + job)
    public val controller: StoreController = StoreController(config.initialFuel)
    /** Fuel remaining, including the burn of a slice that is still in flight. */
    public val fuel: Long
        get() =
            if (budgetFuelEnabled) {
                (controller.fuelValue() - pendingFuelBurn()).coerceAtLeast(0)
            } else {
                controller.fuelValue()
            }
    public val poisoned: Boolean get() = statusState.value == StoreStatus.Poisoned
    public val status: StateFlow<StoreStatus> get() = statusState.asStateFlow()
    public val pendingImport: PendingImport? get() = currentPendingImport
    public val hasRestoredExecution: Boolean get() = restoredExecution

    private val statusState = MutableStateFlow(StoreStatus.Idle)
    private val ownedInstances = mutableListOf<Instance>()
    private val hostSnapshotParticipants = mutableListOf<RegisteredHostSnapshotParticipant>()
    private val executionGate = StoreExecutionGate()
    private var currentPendingImport: PendingImport? = null
    private var running: Boolean = false
    internal var invocationJob: Job? = null
    private var invocationCancellationHandle: DisposableHandle? = null
    @Volatile
    internal var storeCancellationPending: Boolean = false
        private set
    @Volatile
    internal var invocationCancellationPending: Boolean = false
        private set
    private var restoredExecution: Boolean = false
    private var snapshotCaptureActive: Boolean = false
    private var stateRevision: Long = 0

    internal val valueStack: RuntimeValueStack = RuntimeValueStack()
    internal val localStack: RuntimeValueStack = RuntimeValueStack()
    internal val i32ExpressionScratch: IntArray =
        IntArray(MAX_LINEAR_I32_EXPRESSION_DEPTH)
    internal val frames: RuntimeObjectStack<GuestCallFrame> = RuntimeObjectStack()
    private val reusableFrames: RuntimeObjectStack<GuestCallFrame> = RuntimeObjectStack()
    private val reusableControls: RuntimeObjectStack<GuestControlFrame> = RuntimeObjectStack()
    private val linearCodePlanner = LinearCodePlanner()
    internal var instructionsUntilCheckpoint: Int = config.checkpointInterval

    /**
     * `SUSP-1` fuel accounting: instead of a second per-instruction counter,
     * fuel rides the checkpoint countdown. A checkpoint reserves a slice of at
     * most [StoreConfig.checkpointInterval] instructions, capped by the fuel
     * that is actually left; the countdown meters that slice at no extra cost,
     * and the next checkpoint settles what was burned. The cap is what keeps
     * the accounting exact: the final slice ends on precisely the instruction
     * that cannot pay.
     */
    internal val budgetFuelEnabled: Boolean =
        config.fuelEnabled && config.instructionCosts == null
    private var fuelSliceSize: Int = config.checkpointInterval
    private var unsettledFuelBurn: Long = 0

    /**
     * True while the countdown has already been decremented for an instruction
     * that has not executed yet. Such an instruction must stay unpaid: a
     * snapshot taken here resumes by re-executing it, so charging it now would
     * bill it twice. The next slice reserves one instruction for it instead.
     */
    private var fuelPendingCharge: Boolean = false

    internal fun executionContext(callerContext: CoroutineContext): CoroutineContext =
        StoreExecutionInterceptor(
            executionGate,
            callerContext[ContinuationInterceptor],
        )

    init {
        observeStoreCancellation()
    }

    public fun addFuel(amount: Long) {
        controller.addFuel(amount)
        stateRevision++
    }

    public fun requestPause(): PauseHandle {
        val handle = controller.requestPause()
        stateRevision++
        return handle
    }

    /** Cancel the store lifetime and any invocation launched in [scope]. */
    public fun cancel() {
        storeCancellationPending = true
        scope.cancel("kwasm store lifetime ended")
    }

    internal fun register(instance: Instance) {
        ownedInstances.add(instance)
        stateRevision++
    }

    /**
     * Register host-owned state that must be captured with this store.
     *
     * Registering the same participant instance more than once is harmless;
     * replacing an identifier with a different owner is rejected.
     */
    public fun registerHostSnapshotParticipant(participant: HostSnapshotParticipant) {
        registerHostSnapshotParticipantForInstance(instance = null, participant = participant)
    }

    /**
     * Register [participant] as state owned only by [instance].
     *
     * Different instances in one Store may use the same stable participant
     * identifier. A snapshot transaction selects only the registration bound
     * to the exact instance being captured or restored.
     */
    public fun registerHostSnapshotParticipant(
        instance: Instance,
        participant: InstanceScopedHostSnapshotParticipant,
    ) {
        requireOwned(instance)
        registerHostSnapshotParticipantForInstance(instance, participant)
    }

    private fun registerHostSnapshotParticipantForInstance(
        instance: Instance?,
        participant: HostSnapshotParticipant,
    ) {
        val id = participant.id
        require(id.isNotBlank()) { "host snapshot participant id must not be blank" }
        require('\u0000' !in id) {
            "host snapshot participant id must not contain NUL"
        }
        val existing = hostSnapshotParticipants.firstOrNull {
            it.participant.id == id && it.instance === instance
        }
        if (existing != null && existing.participant !== participant) {
            throw SnapshotStateException(
                "host snapshot participant '$id' is already registered by a different owner" +
                    if (instance == null) "" else " for this instance",
            )
        }
        if (existing == null) {
            hostSnapshotParticipants += RegisteredHostSnapshotParticipant(
                participant = participant,
                instance = instance,
            )
            stateRevision++
        }
    }

    /**
     * Return the participant registered under [id], or `null`.
     *
     * This lookup lets a host module reuse Store-owned state without keeping a
     * process-wide side table that would retain completed stores. Like the
     * rest of Store state, callers must observe this only from the Store's
     * confined coroutine.
     */
    public fun hostSnapshotParticipant(
        id: String,
        instance: Instance? = null,
    ): HostSnapshotParticipant? {
        if (instance != null) requireOwned(instance)
        val applicable = hostSnapshotParticipants.filter {
            it.participant.id == id &&
                (it.instance === instance || (instance != null && it.instance == null))
        }
        if (applicable.size > 1) {
            throw SnapshotStateException(
                "multiple host snapshot participants '$id' apply to this instance",
            )
        }
        return applicable.singleOrNull()?.participant
    }

    /**
     * Capture every registered host participant in stable identifier order.
     *
     * The runtime state must already have passed [captureSnapshotState], which
     * establishes that the store is at a defined suspension point.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun captureHostSnapshotState(
        hooks: HostSnapshotHooks?,
    ): List<RuntimeHostSnapshot> =
        captureHostSnapshotStateForInstance(instance = null, hooks = hooks)

    /**
     * Capture registered host state for the exact [instance] being
     * snapshotted.
     *
     * Instance-scoped participants receive this ownership context; legacy
     * store-scoped participants continue to use their context-free callback.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun captureHostSnapshotState(
        instance: Instance,
        hooks: HostSnapshotHooks?,
    ): List<RuntimeHostSnapshot> {
        requireOwned(instance)
        return captureHostSnapshotStateForInstance(instance = instance, hooks = hooks)
    }

    private fun captureHostSnapshotStateForInstance(
        instance: Instance?,
        hooks: HostSnapshotHooks?,
    ): List<RuntimeHostSnapshot> {
        if (!snapshotCaptureActive) {
            throw SnapshotStateException(
                "host snapshot state can be captured only inside captureSnapshotState",
            )
        }
        val applicable = applicableHostSnapshotParticipants(instance)
        requireUniqueHostParticipantIds(applicable)
        return applicable
            .sortedBy { it.participant.id }
            .map { registration ->
                val participant = registration.participant
                val id = participant.id
                val payload =
                    if (participant is InstanceScopedHostSnapshotParticipant) {
                        val scopedInstance = instance
                            ?: throw SnapshotStateException(
                                "host snapshot participant '$id' requires an " +
                                    "instance-scoped capture",
                            )
                        participant.capture(scopedInstance, hooks)
                    } else {
                        participant.capture(hooks)
                    }
                RuntimeHostSnapshot(id, payload)
            }
    }

    /**
     * Resolve and validate all decoded host state before any live state is
     * mutated. The returned commit is invoked only after runtime restoration.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun prepareHostSnapshotRestore(
        snapshots: List<RuntimeHostSnapshot>,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore =
        prepareHostSnapshotRestoreForInstance(
            snapshots = snapshots,
            instance = null,
            hooks = hooks,
        )

    /**
     * Prepare registered host state for the exact restored [instance].
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun prepareHostSnapshotRestore(
        snapshots: List<RuntimeHostSnapshot>,
        instance: Instance,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore {
        requireOwned(instance)
        return prepareHostSnapshotRestoreForInstance(
            snapshots = snapshots,
            instance = instance,
            hooks = hooks,
        )
    }

    private fun prepareHostSnapshotRestoreForInstance(
        snapshots: List<RuntimeHostSnapshot>,
        instance: Instance?,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore {
        if (running || statusState.value !in setOf(StoreStatus.Idle, StoreStatus.Poisoned)) {
            throw SnapshotStateException(
                "cannot prepare host-state restore while store status is ${statusState.value}; " +
                    "the target store must be Idle or Poisoned",
            )
        }
        val preparedRevision = stateRevision
        val snapshotIds = snapshots.map(RuntimeHostSnapshot::participantId)
        if (snapshotIds.size != snapshotIds.toSet().size) {
            throw SnapshotStateException("snapshot contains duplicate host participant identifiers")
        }
        val applicable = applicableHostSnapshotParticipants(instance)
        requireUniqueHostParticipantIds(applicable)
        val participantsById = applicable.associateBy { it.participant.id }
        val registeredIds = participantsById.keys
        val missing = snapshotIds.filterNot(registeredIds::contains)
        val absentFromSnapshot = registeredIds.filterNot(snapshotIds::contains)
        if (missing.isNotEmpty() || absentFromSnapshot.isNotEmpty()) {
            throw SnapshotStateException(
                buildString {
                    append("host snapshot participants do not match the target store")
                    if (missing.isNotEmpty()) {
                        append("; unavailable in target: ")
                        append(missing.sorted().joinToString())
                    }
                    if (absentFromSnapshot.isNotEmpty()) {
                        append("; absent from snapshot: ")
                        append(absentFromSnapshot.sorted().joinToString())
                    }
                },
            )
        }
        val prepared = snapshots
            .sortedBy(RuntimeHostSnapshot::participantId)
            .map { snapshot ->
                val participant =
                    participantsById.getValue(snapshot.participantId).participant
                if (participant is InstanceScopedHostSnapshotParticipant) {
                    val scopedInstance = instance
                        ?: throw SnapshotStateException(
                            "host snapshot participant '${snapshot.participantId}' " +
                                "requires an instance-scoped restore",
                        )
                    participant.prepareRestore(
                        snapshot.payload(),
                        scopedInstance,
                        hooks,
                    )
                } else {
                    participant.prepareRestore(snapshot.payload(), hooks)
                }
            }
        var committed = false
        return HostSnapshotRestore {
            if (committed) {
                throw SnapshotStateException(
                    "prepared host snapshot restore has already been committed",
                )
            }
            if (
                stateRevision != preparedRevision ||
                running ||
                statusState.value !in setOf(StoreStatus.Idle, StoreStatus.Poisoned)
            ) {
                throw SnapshotStateException(
                    "prepared host snapshot restore is stale; Store state changed after preparation",
                )
            }
            prepared.forEach(HostSnapshotRestore::commit)
            committed = true
            stateRevision++
        }
    }

    internal fun beginInvocation(callerJob: Job?) {
        if (poisoned) throw PoisonedStoreException()
        check(!running) { "store is already executing and is confined to one coroutine at a time" }
        running = true
        observeInvocationCancellation(callerJob)
        restoredExecution = false
        statusState.value = StoreStatus.Running
        currentPendingImport = null
        clearGuestFrames()
        valueStack.clear()
        localStack.clear()
        fuelPendingCharge = false
        if (budgetFuelEnabled) {
            takeFuelSlice()
        } else {
            instructionsUntilCheckpoint = config.checkpointInterval
        }
        stateRevision++
    }

    internal fun beginRestoredInvocation(callerJob: Job?): PendingImport? {
        if (poisoned) throw PoisonedStoreException()
        check(!running) { "store is already executing and is confined to one coroutine at a time" }
        if (!restoredExecution) {
            throw SnapshotStateException("store has no restored execution to resume")
        }
        running = true
        observeInvocationCancellation(callerJob)
        restoredExecution = false
        statusState.value = StoreStatus.Running
        stateRevision++
        return currentPendingImport.also { currentPendingImport = null }
    }

    internal fun finishInvocation() {
        if (budgetFuelEnabled) flushFuelBurn()
        currentPendingImport = null
        clearGuestFrames()
        valueStack.clear()
        localStack.clear()
        running = false
        clearInvocationCancellation()
        restoredExecution = false
        if (!poisoned) statusState.value = StoreStatus.Idle
        stateRevision++
    }

    internal fun poison() {
        if (budgetFuelEnabled) flushFuelBurn()
        statusState.value = StoreStatus.Poisoned
        running = false
        currentPendingImport = null
        clearGuestFrames()
        valueStack.clear()
        localStack.clear()
        clearInvocationCancellation()
        restoredExecution = false
        stateRevision++
    }

    internal fun enterHostImport(functionIndex: Int, arguments: List<Value>) {
        currentPendingImport = PendingImport(functionIndex, arguments.toList())
        statusState.value = StoreStatus.InHostImport
    }

    internal fun leaveHostImport() {
        currentPendingImport = null
        if (!poisoned) statusState.value = StoreStatus.Running
    }

    /** Instructions burned but not yet published to the shared counter. */
    private fun pendingFuelBurn(): Long =
        unsettledFuelBurn + (fuelSliceSize - instructionsUntilCheckpoint.coerceAtLeast(0))

    /**
     * Closes the finished slice and reports whether the guest can no longer pay
     * for the instruction it is about to execute. Repeated calls are
     * idempotent, so the fast and slow checkpoint paths may both invoke it.
     */
    internal fun settleFuelRequiresSlowPath(pendingInstruction: Boolean): Boolean {
        val remaining = instructionsUntilCheckpoint
        unsettledFuelBurn += fuelSliceSize - remaining
        if (pendingInstruction && !fuelPendingCharge) {
            unsettledFuelBurn--
            fuelPendingCharge = true
        }
        val normalized = remaining.coerceAtLeast(0)
        instructionsUntilCheckpoint = normalized
        fuelSliceSize = normalized
        return controller.fuelValue() - unsettledFuelBurn <= 0
    }

    /**
     * Reserves the next slice, never more than the fuel that is left. An
     * instruction carried in unpaid takes the first unit of the new slice.
     */
    internal fun takeFuelSlice() {
        val available = (controller.fuelValue() - unsettledFuelBurn).coerceAtLeast(0)
        fuelSliceSize = minOf(config.checkpointInterval.toLong(), available).toInt()
        instructionsUntilCheckpoint =
            if (fuelPendingCharge) fuelSliceSize - 1 else fuelSliceSize
        fuelPendingCharge = false
    }

    /** Publishes the pending burn so cross-thread readers observe exact fuel. */
    private fun flushFuelBurn() {
        settleFuelRequiresSlowPath(pendingInstruction = false)
        if (unsettledFuelBurn > 0) {
            controller.consumeFuel(unsettledFuelBurn)
            unsettledFuelBurn = 0
        }
    }

    /**
     * Hot path selected once per invocation whenever no cost table is set.
     *
     * Keep this to the specified countdown decrement and branch; in
     * particular it performs no config/fuel load per guest instruction. With
     * fuel enabled the same countdown also meters the reserved slice.
     */
    internal fun beforeUnmeteredInstructionRequiresSlowCheckpoint(
        forceCheckpoint: Boolean = false,
    ): Boolean {
        instructionsUntilCheckpoint--
        if (!forceCheckpoint && instructionsUntilCheckpoint > 0) return false
        return checkpointRequiresSlowCheckpoint(pendingInstruction = true)
    }

    /**
     * Completes the common no-pause checkpoint path synchronously. Returning
     * true delegates the uncommon suspending pause path to [checkpoint].
     */
    internal fun checkpointRequiresSlowCheckpoint(pendingInstruction: Boolean): Boolean {
        if (storeCancellationPending) job.ensureActive()
        if (invocationCancellationPending) invocationJob?.ensureActive()
        config.listener?.let { listener ->
            val frame = frames.lastOrNull()
            listener.onCheckpoint(
                this,
                frame?.functionIndex,
                frame?.currentInstructionIndex,
            )
        }
        if (budgetFuelEnabled && settleFuelRequiresSlowPath(pendingInstruction)) return true
        if (controller.hasPauseRequest()) return true
        if (budgetFuelEnabled) {
            takeFuelSlice()
        } else {
            instructionsUntilCheckpoint = config.checkpointInterval
        }
        return false
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun observeStoreCancellation() {
        job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { failure ->
            if (failure != null) storeCancellationPending = true
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun observeInvocationCancellation(callerJob: Job?) {
        invocationCancellationHandle?.dispose()
        invocationCancellationPending = false
        invocationJob = callerJob
        invocationCancellationHandle = callerJob?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { failure ->
            if (failure != null) invocationCancellationPending = true
        }
    }

    private fun clearInvocationCancellation() {
        invocationCancellationHandle?.dispose()
        invocationCancellationHandle = null
        invocationCancellationPending = false
        invocationJob = null
    }

    internal suspend fun beforeMeteredInstruction(
        instruction: Instr,
        forceCheckpoint: Boolean = false,
    ) {
        val cost = config.instructionCosts?.cost(instruction) ?: 1L
        instructionsUntilCheckpoint--
        val needsFuel = !controller.canPayFuel(cost)
        if (needsFuel || forceCheckpoint || instructionsUntilCheckpoint <= 0) {
            checkpoint(handleFuel = needsFuel, requiredFuel = cost)
        }
        check(controller.tryConsumeFuel(cost)) {
            "fuel became unavailable while the store was coroutine-confined"
        }
    }

    internal suspend fun checkpoint(
        handleFuel: Boolean,
        requiredFuel: Long = 1L,
        listenerAlreadyNotified: Boolean = false,
        pendingInstruction: Boolean = false,
    ) {
        job.ensureActive()
        invocationJob?.ensureActive()
        val frame = frames.lastOrNull()
        if (!listenerAlreadyNotified) {
            config.listener?.onCheckpoint(
                this,
                frame?.functionIndex,
                frame?.currentInstructionIndex,
            )
        }

        if (budgetFuelEnabled) {
            if (settleFuelRequiresSlowPath(pendingInstruction)) {
                flushFuelBurn()
                exhaustFuel(frame, 1L)
            }
        } else if (handleFuel) {
            exhaustFuel(frame, requiredFuel)
        }

        if (controller.hasPauseRequest()) {
            statusState.value = StoreStatus.Paused
            controller.awaitResume()
            invocationJob?.ensureActive()
            statusState.value = StoreStatus.Running
        }
        if (budgetFuelEnabled) {
            takeFuelSlice()
        } else {
            instructionsUntilCheckpoint = config.checkpointInterval
        }
    }

    private suspend fun exhaustFuel(frame: GuestCallFrame?, requiredFuel: Long) {
        when (config.fuelExhaustionPolicy) {
            FuelExhaustionPolicy.Trap -> throw OutOfFuel(
                frame?.functionIndex,
                frame?.functionName,
                guestStack(),
            )
            FuelExhaustionPolicy.Suspend -> {
                statusState.value = StoreStatus.WaitingForFuel
                controller.awaitFuel(requiredFuel)
                invocationJob?.ensureActive()
                statusState.value = StoreStatus.Running
            }
        }
    }

    internal fun ensureValueStackLimit() {
        if (valueStack.size > config.limits.maxValueStackSlots) {
            throw ExecutionTrap(
                TrapKind.STACK_EXHAUSTED,
                "value stack has ${valueStack.size} slots; maximum is ${config.limits.maxValueStackSlots}",
            )
        }
    }

    internal fun acquireGuestControl(
        kind: ControlKind,
        body: List<Instr>,
        pc: Int,
        stackBase: Int,
        parameterCount: Int,
        resultCount: Int,
        labelArity: Int,
        exceptionHandler: GuestExceptionHandler? = null,
        caughtException: GuestException? = null,
    ): GuestControlFrame {
        val linearHotCode = linearHotCode(body)
        val control = reusableControls.removeLastOrNull()
            ?: return GuestControlFrame(
                kind,
                body,
                pc,
                stackBase,
                parameterCount,
                resultCount,
                labelArity,
                exceptionHandler,
                caughtException,
                linearHotCode,
            )
        control.kind = kind
        control.body = body
        control.pc = pc
        control.stackBase = stackBase
        control.parameterCount = parameterCount
        control.resultCount = resultCount
        control.labelArity = labelArity
        control.exceptionHandler = exceptionHandler
        control.caughtException = caughtException
        control.linearHotCode = linearHotCode
        return control
    }

    internal fun linearHotCode(body: List<Instr>): LinearHotCode =
        linearCodePlanner.planFor(body)

    internal fun releaseLastGuestControl(frame: GuestCallFrame) {
        releaseGuestControl(frame.controls.removeLast())
    }

    internal fun acquireGuestFrame(
        instance: Instance,
        functionIndex: Int,
        functionName: String?,
        type: FuncType,
        localsBase: Int,
        localCount: Int,
        stackBase: Int,
        root: GuestControlFrame,
    ): GuestCallFrame {
        val frame = reusableFrames.removeLastOrNull()
            ?: GuestCallFrame(
                instance,
                functionIndex,
                functionName,
                type,
                localsBase,
                localCount,
                stackBase,
                RuntimeObjectStack(),
            )
        frame.instance = instance
        frame.functionIndex = functionIndex
        frame.functionName = functionName
        frame.type = type
        frame.localsBase = localsBase
        frame.localCount = localCount
        frame.stackBase = stackBase
        frame.controls.clear()
        frame.controls.addLast(root)
        return frame
    }

    internal fun removeLastGuestFrame(): GuestCallFrame {
        val frame = frames.removeLast()
        localStack.truncate(frame.localsBase)
        while (frame.controls.isNotEmpty()) {
            releaseGuestControl(frame.controls.removeLast())
        }
        return frame
    }

    internal fun releaseGuestFrame(frame: GuestCallFrame) {
        if (reusableFrames.size < MAX_REUSABLE_FRAMES) {
            reusableFrames.addLast(frame)
        }
    }

    private fun releaseGuestControl(control: GuestControlFrame) {
        if (reusableControls.size < MAX_REUSABLE_CONTROLS) {
            control.body = emptyList()
            control.exceptionHandler = null
            control.caughtException = null
            control.linearHotCode = EMPTY_LINEAR_HOT_CODE
            reusableControls.addLast(control)
        }
    }

    private fun clearGuestFrames() {
        while (frames.isNotEmpty()) {
            releaseGuestFrame(removeLastGuestFrame())
        }
    }

    internal fun guestStack(): List<GuestStackFrame> {
        val stack = ArrayList<GuestStackFrame>(frames.size)
        for (index in frames.lastIndex downTo 0) {
            val frame = frames[index]
            stack.add(
                GuestStackFrame(
                    frame.functionIndex,
                    frame.functionName,
                    frame.currentInstructionIndex,
                ),
            )
        }
        return stack
    }

    /**
     * Suspend until the store parks at a snapshot-capturable suspension point.
     *
     * Suspension statuses are published before the executing continuation
     * parks and releases the execution gate, so reacting to [status] alone
     * can race [captureSnapshotState] and fail with "store execution has not
     * parked". This primitive returns only once a capturable status
     * ([StoreStatus.Paused], [StoreStatus.WaitingForFuel], or
     * [StoreStatus.InHostImport]) is published and the execution gate has
     * been released.
     *
     * After this returns, a [captureSnapshotState] call cannot fail with
     * "has not parked" unless the guest resumes in between. While the parked
     * host import or pause is still outstanding the guest cannot resume, so
     * awaiting this and then capturing is race-free; once the caller releases
     * the guest (completing the import, [PauseHandle.resume], [addFuel]) the
     * observed capturability is stale.
     *
     * Waits across [StoreStatus.Idle] and [StoreStatus.Running] for the next
     * capturable suspension point; if none is ever reached this suspends
     * until cancelled. Throws [SnapshotStateException] if the store is or
     * becomes [StoreStatus.Poisoned] while waiting.
     */
    public suspend fun awaitSnapshotCapturable() {
        while (true) {
            val observed = statusState.first {
                it == StoreStatus.Poisoned || it.acceptsSnapshotCapture
            }
            if (observed == StoreStatus.Poisoned) {
                throw SnapshotStateException(
                    "store is poisoned and will not park at a snapshot-capturable suspension point",
                )
            }
            val parkedCapturable = executionGate.withParkedExecution {
                statusState.value.acceptsSnapshotCapture
            }
            if (parkedCapturable) return
        }
    }

    /**
     * Copy all state needed by the optional snapshot codec.
     *
     * The state is coherent only at one of the defined suspension points, so
     * calls made while the guest is running or idle are rejected.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun captureSnapshotState(instance: Instance): RuntimeStoreSnapshot =
        captureSnapshotState(instance) { it }

    /**
     * Capture and consume a coherent suspended-state view as one transaction.
     *
     * [capture] runs while interpreter resumption is excluded. Snapshot codecs
     * use this form because GC objects, host references, and registered host
     * participants may need to be traversed before a fully detached byte
     * representation exists.
     *
     * Capture requires the executing continuation to have parked, which
     * happens strictly after the matching [StoreStatus] is published.
     * Cross-thread callers coordinating through [status] must await
     * [awaitSnapshotCapturable] first instead of capturing on the status
     * observation alone.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun <T> captureSnapshotState(
        instance: Instance,
        capture: (RuntimeStoreSnapshot) -> T,
    ): T {
        requireOwned(instance)
        if (!executionGate.tryAcquireCapture()) {
            throw SnapshotStateException(
                "store execution has not parked; snapshot capture cannot race a running continuation",
            )
        }
        try {
            val currentStatus = statusState.value
            if (!currentStatus.acceptsSnapshotCapture) {
                throw SnapshotStateException(
                    "store status is $currentStatus; " +
                        "snapshot requires Paused, WaitingForFuel, or a parked host import",
                )
            }
            snapshotCaptureActive = true
            return capture(copySnapshotState(instance))
        } finally {
            snapshotCaptureActive = false
            executionGate.releaseCapture()
        }
    }

    private fun framesSpanOtherInstance(instance: Instance): Boolean {
        for (index in 0 until frames.size) {
            if (frames[index].instance !== instance) return true
        }
        return false
    }

    private fun copySnapshotState(instance: Instance): RuntimeStoreSnapshot {
        if (framesSpanOtherInstance(instance)) {
            throw SnapshotStateException(
                "the suspended frame stack spans an instance not being snapshotted",
            )
        }

        val frameSnapshots = ArrayList<RuntimeFrameSnapshot>(frames.size)
        for (frameIndex in 0 until frames.size) {
            val frame = frames[frameIndex]
            val function = instance.module.functions.getOrNull(
                frame.functionIndex - instance.imports.functions.size,
            ) ?: throw SnapshotStateException(
                "frame function ${frame.functionIndex} is not a local function in the snapshotted instance",
            )
            val controlSnapshots = ArrayList<RuntimeControlSnapshot>(frame.controls.size)
            for (controlIndex in 0 until frame.controls.size) {
                val control = frame.controls[controlIndex]
                controlSnapshots.add(
                    RuntimeControlSnapshot(
                        kind = control.kind.toRuntimeKind(),
                        bodyPath = findRuntimeBodyPath(function.body, control.body)
                            ?: throw SnapshotStateException(
                                "cannot locate control body in function ${frame.functionIndex}",
                            ),
                        pc = control.pc,
                        stackBase = control.stackBase,
                        parameterCount = control.parameterCount,
                        resultCount = control.resultCount,
                        labelArity = control.labelArity,
                        caughtException = control.caughtException,
                    ),
                )
            }
            frameSnapshots.add(
                RuntimeFrameSnapshot(
                    functionIndex = frame.functionIndex,
                    locals = localStack.toList(frame.localsBase, frame.localCount),
                    stackBase = frame.stackBase,
                    controls = controlSnapshots,
                ),
            )
        }
        val pending = currentPendingImport?.let {
            RuntimePendingImportSnapshot(it.functionIndex, it.arguments)
        }
        return RuntimeStoreSnapshot(
            instance = instance.captureRuntimeSnapshot(),
            valueStack = valueStack.toList(),
            frames = frameSnapshots,
            pendingImport = pending,
            fuel = fuel,
            instructionsUntilCheckpoint = instructionsUntilCheckpoint,
        )
    }

    /**
     * Install a decoded snapshot after validating the complete state graph.
     *
     * Validation precedes every mutation so a rejected hostile snapshot leaves
     * the target instance and store unchanged.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun restoreSnapshotState(instance: Instance, snapshot: RuntimeStoreSnapshot) {
        validateSnapshotForRestore(instance, snapshot)
        installRuntimeSnapshot(instance, snapshot)
    }

    /**
     * Restore runtime and registered host-participant state as one guarded
     * transaction.
     *
     * Runtime validation and every participant's prepare phase finish before
     * any live state is changed. Host commits run before the infallible runtime
     * installation; a participant that violates its non-throwing commit
     * contract poisons the Store instead of leaving it executable with a
     * partially restored host environment.
     */
    @io.heapy.kwasm.InternalKwasmApi
    public fun restoreSnapshotState(
        instance: Instance,
        snapshot: RuntimeStoreSnapshot,
        hostSnapshots: List<RuntimeHostSnapshot>,
        hooks: HostSnapshotHooks?,
    ) {
        validateSnapshotForRestore(instance, snapshot)
        val hostRestore = prepareHostSnapshotRestore(
            snapshots = hostSnapshots,
            instance = instance,
            hooks = hooks,
        )
        try {
            hostRestore.commit()
            installRuntimeSnapshot(instance, snapshot)
        } catch (failure: Throwable) {
            poison()
            throw failure
        }
    }

    /**
     * Instantiate a module whose mutable storage is local, then restore it.
     *
     * Imported memories and tables are rejected because normal WebAssembly
     * instantiation can initialize those host-owned objects before snapshot
     * validation finishes. Embedders with imported mutable storage must
     * instantiate explicitly and use the existing-instance restore path.
     */
    public fun instantiateAndRestoreSnapshot(
        module: Module,
        imports: ResolvedImports,
        snapshot: RuntimeStoreSnapshot,
    ): Instance {
        if (module.importedMemoryCount != 0 || module.importedTableCount != 0) {
            throw SnapshotStateException(
                "new-instance snapshot restore cannot use imported memories or tables; " +
                    "instantiate explicitly and restore into that instance",
            )
        }
        val instance = Instance(this, module, imports)
        try {
            restoreSnapshotState(
                instance = instance,
                snapshot = snapshot,
                hostSnapshots = emptyList(),
                hooks = null,
            )
            return instance
        } catch (failure: Throwable) {
            ownedInstances.remove(instance)
            stateRevision++
            throw failure
        }
    }

    private fun validateSnapshotForRestore(
        instance: Instance,
        snapshot: RuntimeStoreSnapshot,
    ) {
        requireOwned(instance)
        if (running || statusState.value !in setOf(StoreStatus.Idle, StoreStatus.Poisoned)) {
            throw SnapshotStateException(
                "cannot restore while store status is ${statusState.value}; " +
                    "the target store must be Idle or Poisoned",
            )
        }
        instance.rebindAndValidateSnapshotGraph(snapshot)
        validateRuntimeSnapshot(instance, snapshot)
    }

    private fun installRuntimeSnapshot(
        instance: Instance,
        snapshot: RuntimeStoreSnapshot,
    ) {
        instance.restoreRuntimeSnapshot(snapshot.instance)
        controller.restoreFuel(snapshot.fuel)
        clearGuestFrames()
        valueStack.clear()
        snapshot.valueStack().forEach(valueStack::addLast)
        localStack.clear()
        snapshot.frames.forEach { frameSnapshot ->
            val type = instance.functionType(frameSnapshot.functionIndex)
            val function = instance.module.functions[
                frameSnapshot.functionIndex - instance.imports.functions.size
            ]
            val controls = RuntimeObjectStack<GuestControlFrame>()
            frameSnapshot.controls.forEach { control ->
                val body = resolveRuntimeBody(function.body, control.bodyPath)
                controls.addLast(
                    GuestControlFrame(
                        kind = control.kind.toControlKind(),
                        body = body,
                        pc = control.pc,
                        stackBase = control.stackBase,
                        parameterCount = control.parameterCount,
                        resultCount = control.resultCount,
                        labelArity = control.labelArity,
                        exceptionHandler = resolveRuntimeExceptionHandler(
                            function.body,
                            control.kind,
                            control.bodyPath,
                        ),
                        caughtException = control.caughtException,
                        linearHotCode = linearHotCode(body),
                    ),
                )
            }
            val locals = frameSnapshot.locals()
            val localsBase = localStack.size
            locals.forEach(localStack::addLast)
            frames.addLast(
                GuestCallFrame(
                    instance = instance,
                    functionIndex = frameSnapshot.functionIndex,
                    functionName = instance.module.nameSection
                        ?.functionNames
                        ?.get(frameSnapshot.functionIndex),
                    type = type,
                    localsBase = localsBase,
                    localCount = locals.size,
                    stackBase = frameSnapshot.stackBase,
                    controls = controls,
                ),
            )
        }
        currentPendingImport = snapshot.pendingImport?.let {
            PendingImport(it.functionIndex, it.arguments())
        }
        unsettledFuelBurn = 0
        fuelPendingCharge = false
        instructionsUntilCheckpoint =
            if (budgetFuelEnabled) {
                minOf(snapshot.instructionsUntilCheckpoint.toLong(), snapshot.fuel).toInt()
            } else {
                snapshot.instructionsUntilCheckpoint
            }
        fuelSliceSize = instructionsUntilCheckpoint
        running = false
        restoredExecution = true
        statusState.value = StoreStatus.Paused
        stateRevision++
    }

    private fun validateRuntimeSnapshot(instance: Instance, snapshot: RuntimeStoreSnapshot) {
        if (snapshot.fuel < 0) {
            throw SnapshotStateException("fuel is negative")
        }
        if (
            snapshot.instructionsUntilCheckpoint < 0 ||
            snapshot.instructionsUntilCheckpoint > config.checkpointInterval
        ) {
            throw SnapshotStateException(
                "checkpoint countdown ${snapshot.instructionsUntilCheckpoint} is outside " +
                    "0..${config.checkpointInterval}",
            )
        }
        val values = snapshot.valueStack()
        if (values.size > config.limits.maxValueStackSlots) {
            throw SnapshotStateException(
                "value stack has ${values.size} slots; limit is ${config.limits.maxValueStackSlots}",
            )
        }
        values.forEachIndexed { index, value ->
            instance.validateSnapshotValue(value, "value stack slot $index")
        }
        if (snapshot.frames.size > config.limits.maxFrames) {
            throw SnapshotStateException(
                "frame stack has ${snapshot.frames.size} frames; limit is ${config.limits.maxFrames}",
            )
        }
        snapshot.frames.forEachIndexed { frameIndex, frame ->
            val localFunctionIndex = frame.functionIndex - instance.imports.functions.size
            val function = instance.module.functions.getOrNull(localFunctionIndex)
                ?: throw SnapshotStateException(
                    "frame $frameIndex refers to non-local function ${frame.functionIndex}",
                )
            val type = instance.functionType(frame.functionIndex)
            val locals = frame.locals()
            val expectedLocalTypes = type.params + function.locals
            if (locals.size != expectedLocalTypes.size) {
                throw SnapshotStateException(
                    "frame $frameIndex has ${locals.size} locals; expected ${expectedLocalTypes.size}",
                )
            }
            locals.forEachIndexed { localIndex, value ->
                if (!value.matches(expectedLocalTypes[localIndex], instance.module)) {
                    throw SnapshotStateException(
                        "frame $frameIndex local $localIndex has ${value.valueType()}, " +
                            "expected ${expectedLocalTypes[localIndex]}",
                    )
                }
                instance.validateSnapshotValue(value, "frame $frameIndex local $localIndex")
            }
            if (frame.stackBase !in 0..values.size) {
                throw SnapshotStateException(
                    "frame $frameIndex stack base ${frame.stackBase} is outside 0..${values.size}",
                )
            }
            if (frame.controls.isEmpty()) {
                throw SnapshotStateException("frame $frameIndex has no control frames")
            }
            if (frame.controls.first().kind != RuntimeControlKind.Function) {
                throw SnapshotStateException("frame $frameIndex does not start with a function control")
            }
            frame.controls.forEachIndexed { controlIndex, control ->
                val body = resolveRuntimeBody(function.body, control.bodyPath)
                if (control.pc !in 0..body.size) {
                    throw SnapshotStateException(
                        "frame $frameIndex control $controlIndex pc ${control.pc} " +
                            "is outside 0..${body.size}",
                    )
                }
                if (control.stackBase !in 0..values.size) {
                    throw SnapshotStateException(
                        "frame $frameIndex control $controlIndex stack base ${control.stackBase} " +
                            "is outside 0..${values.size}",
                    )
                }
                if (
                    control.parameterCount < 0 ||
                    control.resultCount < 0 ||
                    control.labelArity < 0
                ) {
                    throw SnapshotStateException(
                        "frame $frameIndex control $controlIndex has a negative arity",
                    )
                }
            }
        }
        snapshot.pendingImport?.let { pending ->
            if (!instance.isImportedFunction(pending.functionIndex)) {
                throw SnapshotStateException(
                    "pending import function ${pending.functionIndex} is not an imported function",
                )
            }
            val arguments = pending.arguments()
            val type = instance.functionType(pending.functionIndex)
            if (arguments.size != type.params.size) {
                throw SnapshotStateException(
                    "pending import has ${arguments.size} arguments; expected ${type.params.size}",
                )
            }
            arguments.forEachIndexed { index, value ->
                if (!value.matches(type.params[index], instance.module)) {
                    throw SnapshotStateException(
                        "pending import argument $index has ${value.valueType()}, expected ${type.params[index]}",
                    )
                }
                instance.validateSnapshotValue(value, "pending import argument $index")
            }
        }
        instance.validateRuntimeSnapshot(snapshot.instance)
    }

    private fun applicableHostSnapshotParticipants(
        instance: Instance?,
    ): List<RegisteredHostSnapshotParticipant> =
        hostSnapshotParticipants.filter { registration ->
            if (instance == null) {
                registration.instance == null
            } else {
                registration.instance == null || registration.instance === instance
            }
        }

    private fun requireUniqueHostParticipantIds(
        participants: List<RegisteredHostSnapshotParticipant>,
    ) {
        val duplicate = participants
            .groupBy { it.participant.id }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        if (duplicate != null) {
            throw SnapshotStateException(
                "multiple host snapshot participants '$duplicate' apply to this instance",
            )
        }
    }

    private fun requireOwned(instance: Instance) {
        if (instance.store !== this || instance !in ownedInstances) {
            throw SnapshotStateException("instance is not owned by this store")
        }
    }

    private data class RegisteredHostSnapshotParticipant(
        val participant: HostSnapshotParticipant,
        val instance: Instance?,
    )
}

private val StoreStatus.acceptsSnapshotCapture: Boolean
    get() = this == StoreStatus.Paused ||
        this == StoreStatus.WaitingForFuel ||
        this == StoreStatus.InHostImport

private const val MAX_REUSABLE_FRAMES: Int = 256
private const val MAX_REUSABLE_CONTROLS: Int = 1_024

private fun ControlKind.toRuntimeKind(): RuntimeControlKind = when (this) {
    ControlKind.Function -> RuntimeControlKind.Function
    ControlKind.Block -> RuntimeControlKind.Block
    ControlKind.Loop -> RuntimeControlKind.Loop
    ControlKind.If -> RuntimeControlKind.If
    ControlKind.TryTable -> RuntimeControlKind.TryTable
    ControlKind.LegacyTry -> RuntimeControlKind.LegacyTry
}

private fun RuntimeControlKind.toControlKind(): ControlKind = when (this) {
    RuntimeControlKind.Function -> ControlKind.Function
    RuntimeControlKind.Block -> ControlKind.Block
    RuntimeControlKind.Loop -> ControlKind.Loop
    RuntimeControlKind.If -> ControlKind.If
    RuntimeControlKind.TryTable -> ControlKind.TryTable
    RuntimeControlKind.LegacyTry -> ControlKind.LegacyTry
}

private fun findRuntimeBodyPath(
    root: List<Instr>,
    target: List<Instr>,
): List<RuntimeBodyStep>? {
    if (root === target) return emptyList()
    root.forEachIndexed { index, instruction ->
        val candidates: List<Triple<RuntimeBodyBranch, Int, List<Instr>>> = when (instruction) {
            is Instr.Block -> listOf(Triple(RuntimeBodyBranch.Body, -1, instruction.body))
            is Instr.Loop -> listOf(Triple(RuntimeBodyBranch.Body, -1, instruction.body))
            is Instr.If -> listOf(
                Triple(RuntimeBodyBranch.Then, -1, instruction.thenBody),
                Triple(RuntimeBodyBranch.Else, -1, instruction.elseBody),
            )
            is Instr.TryTable ->
                listOf(Triple(RuntimeBodyBranch.Body, -1, instruction.body))
            is Instr.LegacyTry ->
                buildList {
                    add(Triple(RuntimeBodyBranch.Body, -1, instruction.body))
                    instruction.catches.forEachIndexed { catchIndex, catch ->
                        add(Triple(RuntimeBodyBranch.Catch, catchIndex, catch.body))
                    }
                    instruction.catchAll?.let {
                        add(Triple(RuntimeBodyBranch.CatchAll, -1, it))
                    }
                }
            else -> emptyList()
        }
        for ((branch, branchIndex, child) in candidates) {
            val suffix = findRuntimeBodyPath(child, target) ?: continue
            return listOf(RuntimeBodyStep(index, branch, branchIndex)) + suffix
        }
    }
    return null
}

private fun resolveRuntimeBody(
    root: List<Instr>,
    path: List<RuntimeBodyStep>,
): List<Instr> {
    var body = root
    path.forEachIndexed { depth, step ->
        val instruction = body.getOrNull(step.instructionIndex)
            ?: throw SnapshotStateException(
                "control body path step $depth uses instruction ${step.instructionIndex}; " +
                    "body size is ${body.size}",
            )
        body = when (step.branch) {
            RuntimeBodyBranch.Body -> when (instruction) {
                is Instr.Block -> instruction.body
                is Instr.Loop -> instruction.body
                else -> throw SnapshotStateException(
                    "control body path step $depth requests Body from opcode 0x" +
                        instruction.opcode.toString(16),
                )
            }
            RuntimeBodyBranch.Then -> (instruction as? Instr.If)?.thenBody
                ?: throw SnapshotStateException(
                    "control body path step $depth requests Then from opcode 0x" +
                        instruction.opcode.toString(16),
                )
            RuntimeBodyBranch.Else -> (instruction as? Instr.If)?.elseBody
                ?: throw SnapshotStateException(
                    "control body path step $depth requests Else from opcode 0x" +
                        instruction.opcode.toString(16),
                )
            RuntimeBodyBranch.Catch -> {
                val legacy = instruction as? Instr.LegacyTry
                    ?: throw SnapshotStateException(
                        "control body path step $depth requests Catch from opcode 0x" +
                            instruction.opcode.toString(16),
                    )
                legacy.catches.getOrNull(step.branchIndex)?.body
                    ?: throw SnapshotStateException(
                        "control body path step $depth requests catch ${step.branchIndex}; " +
                            "catch count is ${legacy.catches.size}",
                    )
            }
            RuntimeBodyBranch.CatchAll -> (instruction as? Instr.LegacyTry)?.catchAll
                ?: throw SnapshotStateException(
                    "control body path step $depth requests CatchAll from opcode 0x" +
                        instruction.opcode.toString(16),
                )
        }
    }
    return body
}

private fun resolveRuntimeExceptionHandler(
    root: List<Instr>,
    kind: RuntimeControlKind,
    path: List<RuntimeBodyStep>,
): GuestExceptionHandler? {
    if (kind != RuntimeControlKind.TryTable && kind != RuntimeControlKind.LegacyTry) return null
    val finalStep = path.lastOrNull()
        ?: throw SnapshotStateException("$kind control has no source instruction path")
    var parent = root
    path.dropLast(1).forEach { step ->
        parent = resolveRuntimeBody(parent, listOf(step))
    }
    val source = parent.getOrNull(finalStep.instructionIndex)
        ?: throw SnapshotStateException(
            "$kind control source instruction ${finalStep.instructionIndex} is out of bounds",
        )
    return when (kind) {
        RuntimeControlKind.TryTable -> {
            val instruction = source as? Instr.TryTable
                ?: throw SnapshotStateException("TryTable control path does not identify try_table")
            GuestExceptionHandler.Standard(instruction.catches)
        }
        RuntimeControlKind.LegacyTry -> {
            val instruction = source as? Instr.LegacyTry
                ?: throw SnapshotStateException("LegacyTry control path does not identify legacy try")
            if (finalStep.branch != RuntimeBodyBranch.Body) {
                null
            } else {
                GuestExceptionHandler.Legacy(
                    instruction.catches,
                    instruction.catchAll,
                    instruction.delegateDepth,
                )
            }
        }
    }
}
