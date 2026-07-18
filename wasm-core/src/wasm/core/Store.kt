package io.heapy.kwasm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Policy used when an enabled fuel counter cannot pay for the next instruction. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class FuelExhaustionPolicy {
    Trap,
    Suspend,
}

/** Optional instruction-cost override. The default cost is one for every instruction. */
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

/** Coarse store state suitable for monitoring and snapshot coordination. */
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
) {
    /** Lifetime job for executions launched through [scope]. */
    public val job: Job = SupervisorJob(parentContext[Job])
    /** Structured-concurrency scope owned by this store. */
    public val scope: CoroutineScope = CoroutineScope(parentContext + job)
    public val controller: StoreController = StoreController(config.initialFuel)
    public val fuel: Long get() = controller.fuel.value
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
    internal val frames: ArrayDeque<GuestCallFrame> = ArrayDeque()
    private val reusableFrames: ArrayDeque<GuestCallFrame> = ArrayDeque()
    private val reusableControls: ArrayDeque<GuestControlFrame> = ArrayDeque()
    private val linearHotPlans: MutableList<Pair<List<Instr>, ByteArray>> = mutableListOf()
    internal var instructionsUntilCheckpoint: Int = config.checkpointInterval
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
        instructionsUntilCheckpoint = config.checkpointInterval
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

    /**
     * Hot path selected once per invocation when fuel is disabled.
     *
     * Keep this to the specified countdown decrement and branch; in
     * particular it performs no config/fuel load per guest instruction.
     */
    internal fun beforeUnmeteredInstructionRequiresSlowCheckpoint(
        forceCheckpoint: Boolean = false,
    ): Boolean {
        instructionsUntilCheckpoint--
        if (!forceCheckpoint && instructionsUntilCheckpoint > 0) return false
        return checkpointRequiresSlowCheckpoint()
    }

    /**
     * Completes the common no-pause checkpoint path synchronously. Returning
     * true delegates the uncommon suspending pause path to [checkpoint].
     */
    internal fun checkpointRequiresSlowCheckpoint(): Boolean {
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
        if (controller.hasPauseRequest()) return true
        instructionsUntilCheckpoint = config.checkpointInterval
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

        if (handleFuel) {
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

        if (controller.hasPauseRequest()) {
            statusState.value = StoreStatus.Paused
            controller.awaitResume()
            invocationJob?.ensureActive()
            statusState.value = StoreStatus.Running
        }
        instructionsUntilCheckpoint = config.checkpointInterval
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
        val linearHotPlan = linearHotPlan(body)
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
                linearHotPlan,
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
        control.linearHotPlan = linearHotPlan
        return control
    }

    internal fun linearHotPlan(body: List<Instr>): ByteArray {
        linearHotPlans.firstOrNull { (candidate, _) -> candidate === body }
            ?.let { return it.second }
        val plan = ByteArray(body.size)
        for (index in body.indices) {
            val first = body[index]
            val second = body.getOrNull(index + 1)
            val third = body.getOrNull(index + 2)
            val expressionLength = body.i32ExpressionLengthFrom(index)
            if (expressionLength >= MIN_LINEAR_I32_EXPRESSION_LENGTH) {
                plan[index] = (LINEAR_PLAN_I32_EXPRESSION_OFFSET + expressionLength).toByte()
                continue
            }
            if (
                first is Instr.Simple &&
                first.opcode.isPlannedI32Binary() &&
                second is Instr.FcIndex &&
                second.opcode == 0x21
            ) {
                plan[index] = LINEAR_PLAN_STACK_BINARY_SET
                continue
            }
            if (
                first is Instr.I32Const &&
                second is Instr.Simple &&
                second.opcode.isPlannedI32Binary()
            ) {
                plan[index] =
                    if (third is Instr.FcIndex && third.opcode == 0x21) {
                        LINEAR_PLAN_CONST_BINARY_SET
                    } else {
                        LINEAR_PLAN_CONST_BINARY
                    }
                continue
            }
            if (first !is Instr.FcIndex || first.opcode != 0x20) continue
            if (
                second is Instr.Simple &&
                second.opcode.isPlannedI32Binary()
            ) {
                plan[index] =
                    if (third is Instr.FcIndex && third.opcode == 0x21) {
                        LINEAR_PLAN_LOCAL_BINARY_SET
                    } else {
                        LINEAR_PLAN_LOCAL_BINARY
                    }
                continue
            }
            if (
                second is Instr.Load &&
                second.opcode.isPlannedI32Load()
            ) {
                if (
                    third is Instr.Load &&
                    third.opcode.isPlannedI32Load()
                ) {
                    val fourth = body.getOrNull(index + 3)
                    plan[index] = when {
                        fourth is Instr.FcIndex && fourth.opcode == 0x21 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_SET
                        fourth is Instr.FcIndex && fourth.opcode == 0x22 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_TEE
                        else -> LINEAR_PLAN_LOCAL_I32_LOAD_LOAD
                    }
                } else {
                    plan[index] = when {
                        third is Instr.FcIndex && third.opcode == 0x21 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_SET
                        third is Instr.FcIndex && third.opcode == 0x22 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_TEE
                        else -> LINEAR_PLAN_LOCAL_I32_LOAD
                    }
                }
                continue
            }
            if (
                second !is Instr.I32Const &&
                (second !is Instr.FcIndex || second.opcode != 0x20)
            ) {
                continue
            }
            val operation = third as? Instr.Simple ?: continue
            if (operation.opcode.isPlannedI32Binary()) {
                val fourth = body.getOrNull(index + 3)
                plan[index] = when {
                    fourth is Instr.FcIndex && fourth.opcode == 0x21 ->
                        LINEAR_PLAN_PRODUCERS_BINARY_SET
                    fourth is Instr.Simple && fourth.opcode.isPlannedI32Binary() -> {
                        val fifth = body.getOrNull(index + 4)
                        when {
                            fifth is Instr.FcIndex && fifth.opcode == 0x21 ->
                                LINEAR_PLAN_PRODUCERS_BINARY_BINARY_SET
                            fifth is Instr.Simple && fifth.opcode.isPlannedI32Binary() -> {
                                val sixth = body.getOrNull(index + 5)
                                if (sixth is Instr.FcIndex && sixth.opcode == 0x21) {
                                    LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY_SET
                                } else {
                                    LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY
                                }
                            }
                            else -> LINEAR_PLAN_PRODUCERS_BINARY_BINARY
                        }
                    }
                    else -> LINEAR_PLAN_PRODUCERS_BINARY
                }
            }
        }
        linearHotPlans.add(body to plan)
        return plan
    }

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
                ArrayDeque(),
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
            control.linearHotPlan = EMPTY_LINEAR_HOT_PLAN
            reusableControls.addLast(control)
        }
    }

    private fun clearGuestFrames() {
        while (frames.isNotEmpty()) {
            releaseGuestFrame(removeLastGuestFrame())
        }
    }

    internal fun guestStack(): List<GuestStackFrame> =
        frames.asReversed().map {
            GuestStackFrame(it.functionIndex, it.functionName, it.currentInstructionIndex)
        }

    /**
     * Copy all state needed by the optional snapshot codec.
     *
     * The state is coherent only at one of the defined suspension points, so
     * calls made while the guest is running or idle are rejected.
     */
    public fun captureSnapshotState(instance: Instance): RuntimeStoreSnapshot =
        captureSnapshotState(instance) { it }

    /**
     * Capture and consume a coherent suspended-state view as one transaction.
     *
     * [capture] runs while interpreter resumption is excluded. Snapshot codecs
     * use this form because GC objects, host references, and registered host
     * participants may need to be traversed before a fully detached byte
     * representation exists.
     */
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
            if (
                currentStatus != StoreStatus.Paused &&
                currentStatus != StoreStatus.WaitingForFuel &&
                currentStatus != StoreStatus.InHostImport
            ) {
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

    private fun copySnapshotState(instance: Instance): RuntimeStoreSnapshot {
        if (frames.any { it.instance !== instance }) {
            throw SnapshotStateException(
                "the suspended frame stack spans an instance not being snapshotted",
            )
        }

        val frameSnapshots = frames.map { frame ->
            val function = instance.module.functions.getOrNull(
                frame.functionIndex - instance.imports.functions.size,
            ) ?: throw SnapshotStateException(
                "frame function ${frame.functionIndex} is not a local function in the snapshotted instance",
            )
            RuntimeFrameSnapshot(
                functionIndex = frame.functionIndex,
                locals = localStack.toList(frame.localsBase, frame.localCount),
                stackBase = frame.stackBase,
                controls = frame.controls.map { control ->
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
                    )
                },
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
            val controls = ArrayDeque<GuestControlFrame>()
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
                        linearHotPlan = linearHotPlan(body),
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
        instructionsUntilCheckpoint = snapshot.instructionsUntilCheckpoint
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

/** Excludes snapshot traversal from synchronous interpreter segments. */
private class StoreExecutionGate {
    private val mutex = Mutex()

    fun tryAcquireCapture(): Boolean = mutex.tryLock()

    fun releaseCapture() {
        mutex.unlock()
    }

    fun <T> resumeSegment(
        continuation: Continuation<T>,
        result: Result<T>,
        delegate: ContinuationInterceptor?,
    ) {
        if (mutex.tryLock()) {
            resumeLocked(continuation, result)
            return
        }

        /*
         * Do not park the dispatcher thread when snapshot traversal owns the
         * store. The waiter suspends on the mutex and resumes the raw
         * continuation on the same dispatcher/interceptor it originally used.
         */
        val waiterContext =
            continuation.context.minusKey(ContinuationInterceptor) +
                NonCancellable +
                (delegate ?: Dispatchers.Default)
        CoroutineScope(waiterContext).launch {
            mutex.lock()
            resumeLocked(continuation, result)
        }
    }

    private fun <T> resumeLocked(
        continuation: Continuation<T>,
        result: Result<T>,
    ) {
        try {
            continuation.resumeWith(result)
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * Delegates scheduling to the invocation's original interceptor, but inserts
 * the store gate immediately around each synchronous continuation segment.
 */
private class StoreExecutionInterceptor(
    private val gate: StoreExecutionGate,
    private val delegate: ContinuationInterceptor?,
) : ContinuationInterceptor {
    override val key: CoroutineContext.Key<*>
        get() = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        val gated = StoreExecutionContinuation(continuation, gate, delegate)
        return delegate?.interceptContinuation(gated) ?: gated
    }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        delegate?.releaseInterceptedContinuation(continuation)
    }
}

private class StoreExecutionContinuation<T>(
    private val continuation: Continuation<T>,
    private val gate: StoreExecutionGate,
    private val delegate: ContinuationInterceptor?,
) : Continuation<T> {
    override val context: CoroutineContext
        get() = continuation.context

    override fun resumeWith(result: Result<T>) {
        gate.resumeSegment(continuation, result, delegate)
    }
}

internal enum class ControlKind {
    Function,
    Block,
    Loop,
    If,
    TryTable,
    LegacyTry,
}

internal sealed class GuestExceptionHandler {
    data class Standard(val catches: List<CatchClause>) : GuestExceptionHandler()
    data class Legacy(
        val catches: List<LegacyCatch>,
        val catchAll: List<Instr>?,
        val delegateDepth: Int?,
    ) : GuestExceptionHandler()
}

internal class GuestControlFrame(
    var kind: ControlKind,
    var body: List<Instr>,
    var pc: Int,
    var stackBase: Int,
    var parameterCount: Int,
    var resultCount: Int,
    var labelArity: Int,
    var exceptionHandler: GuestExceptionHandler? = null,
    var caughtException: GuestException? = null,
    var linearHotPlan: ByteArray = EMPTY_LINEAR_HOT_PLAN,
)

internal class GuestCallFrame(
    var instance: Instance,
    var functionIndex: Int,
    var functionName: String?,
    var type: FuncType,
    var localsBase: Int,
    var localCount: Int,
    var stackBase: Int,
    val controls: ArrayDeque<GuestControlFrame>,
) {
    val currentInstructionIndex: Int
        get() = (controls.lastOrNull()?.pc ?: 1) - 1
}

private const val MAX_REUSABLE_FRAMES: Int = 256
private const val MAX_REUSABLE_CONTROLS: Int = 1_024
private val EMPTY_LINEAR_HOT_PLAN: ByteArray = ByteArray(0)

internal const val MAX_LINEAR_I32_EXPRESSION_DEPTH: Int = 8
internal const val LINEAR_PLAN_I32_EXPRESSION_OFFSET: Int = 16
private const val MIN_LINEAR_I32_EXPRESSION_LENGTH: Int = 6
private const val MAX_LINEAR_I32_EXPRESSION_LENGTH: Int =
    Byte.MAX_VALUE - LINEAR_PLAN_I32_EXPRESSION_OFFSET

internal const val LINEAR_PLAN_STACK_BINARY_SET: Byte = -2
internal const val LINEAR_PLAN_PRODUCERS_BINARY_SET: Byte = -4
internal const val LINEAR_PLAN_LOCAL_BINARY_SET: Byte = -5
internal const val LINEAR_PLAN_CONST_BINARY_SET: Byte = -6
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY_SET: Byte = -7
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY_SET: Byte = -8
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_SET: Byte = -9
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_TEE: Byte = -10
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_SET: Byte = -11
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_TEE: Byte = -12
internal const val LINEAR_PLAN_CONST_BINARY: Byte = 2
internal const val LINEAR_PLAN_PRODUCERS_BINARY: Byte = 3
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY: Byte = 4
internal const val LINEAR_PLAN_LOCAL_BINARY: Byte = 5
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY: Byte = 6
internal const val LINEAR_PLAN_LOCAL_I32_LOAD: Byte = 7
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_LOAD: Byte = 8

private fun Int.isPlannedI32Binary(): Boolean =
    this in 0x46..0x4F || this in 0x6A..0x78

private fun Int.isPlannedI32Load(): Boolean =
    this == 0x28 || this in 0x2C..0x2F

private fun List<Instr>.i32ExpressionLengthFrom(start: Int): Int {
    var depth = 0
    val endExclusive =
        minOf(size, start + MAX_LINEAR_I32_EXPRESSION_LENGTH)
    for (index in start until endExclusive) {
        when (val instruction = this[index]) {
            is Instr.I32Const -> {
                if (depth == MAX_LINEAR_I32_EXPRESSION_DEPTH) return 0
                depth++
            }
            is Instr.FcIndex -> when (instruction.opcode) {
                0x20 -> {
                    if (depth == MAX_LINEAR_I32_EXPRESSION_DEPTH) return 0
                    depth++
                }
                0x21 -> {
                    if (depth != 1) return 0
                    return index - start + 1
                }
                else -> return 0
            }
            is Instr.Simple -> {
                if (!instruction.opcode.isPlannedI32Binary() || depth < 2) return 0
                depth--
            }
            else -> return 0
        }
    }
    return 0
}

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
