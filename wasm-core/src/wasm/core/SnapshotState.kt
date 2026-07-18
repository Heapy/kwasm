package io.heapy.kwasm

/**
 * Marker implemented by snapshot-codec hooks that can also serve optional
 * host-state participants registered with a [Store].
 *
 * Each participant defines a more specific sub-interface for the resources it
 * owns. Keeping this marker in `:core` lets host modules participate without
 * depending on the optional snapshot codec.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface HostSnapshotHooks

/**
 * Host-owned mutable state that must travel with a runtime snapshot.
 *
 * [capture] returns an opaque, deterministic payload owned by this
 * participant. [prepareRestore] must parse and validate the complete payload,
 * resolve every host resource through [hooks], and return a commit whose
 * execution is non-failing and does not suspend.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface HostSnapshotParticipant {
    /** Stable, globally unique identifier stored in the snapshot. */
    public val id: String

    /** Capture this participant while its owning store is suspended. */
    public fun capture(hooks: HostSnapshotHooks?): ByteArray

    /**
     * Prepare restoration without mutating live host state.
     *
     * Returning normally promises that [HostSnapshotRestore.commit] is a
     * non-failing map/state swap. A participant must fail here when hooks or
     * rehydrated resources are unavailable.
     */
    public fun prepareRestore(
        payload: ByteArray,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore
}

/**
 * Host-owned state whose portable representation is bound to one [Instance].
 *
 * A store can own multiple instances. Implement this interface when a
 * participant must distinguish state belonging to the instance being
 * snapshotted from state belonging to another instance in the same store.
 * Snapshot codecs call these overloads with the exact source or target
 * instance; the context-free [HostSnapshotParticipant] methods deliberately
 * fail so callers cannot accidentally erase that ownership boundary.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface InstanceScopedHostSnapshotParticipant : HostSnapshotParticipant {
    /** Capture state owned by [instance]. */
    public fun capture(
        instance: Instance,
        hooks: HostSnapshotHooks?,
    ): ByteArray

    /** Prepare state that will be owned by [instance] after restoration. */
    public fun prepareRestore(
        payload: ByteArray,
        instance: Instance,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore

    override fun capture(hooks: HostSnapshotHooks?): ByteArray =
        throw SnapshotStateException(
            "host snapshot participant '$id' requires an instance-scoped capture",
        )

    override fun prepareRestore(
        payload: ByteArray,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore =
        throw SnapshotStateException(
            "host snapshot participant '$id' requires an instance-scoped restore",
        )
}

/**
 * A fully validated host-state restoration ready for an infallible commit.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface HostSnapshotRestore {
    public fun commit()
}

/** Immutable opaque host-participant state carried by `:snapshot`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeHostSnapshot(
    public val participantId: String,
    payload: ByteArray,
) {
    private val bytes: ByteArray = payload.copyOf()

    public val size: Int get() = bytes.size
    public fun payload(): ByteArray = bytes.copyOf()
}

/**
 * Immutable bridge between the runtime and the optional `:snapshot` codec.
 *
 * These objects describe runtime state only; they deliberately do not define a
 * wire format. Byte arrays and mutable value payloads are copied at every
 * boundary so callers cannot mutate a suspended store through a snapshot view.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeMemorySnapshot(bytes: ByteArray) {
    private val contents: ByteArray = bytes.copyOf()

    public val size: Int get() = contents.size
    public fun bytes(): ByteArray = contents.copyOf()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeTableSnapshot(values: List<Value.Ref>) {
    private val elements: List<Value.Ref> = values.map { copySnapshotValue(it) as Value.Ref }

    public val size: Int get() = elements.size
    public fun values(): List<Value.Ref> = elements.map { copySnapshotValue(it) as Value.Ref }
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeInstanceSnapshot(
    memories: List<RuntimeMemorySnapshot>,
    tables: List<RuntimeTableSnapshot>,
    globals: List<Value>,
    elementSegmentsDropped: List<Boolean>,
    dataSegments: List<ByteArray>,
) {
    public val memories: List<RuntimeMemorySnapshot> =
        memories.map { RuntimeMemorySnapshot(it.bytes()) }
    public val tables: List<RuntimeTableSnapshot> =
        tables.map { RuntimeTableSnapshot(it.values()) }
    private val globalValues: List<Value> = globals.map(::copySnapshotValue)
    public val elementSegmentsDropped: List<Boolean> = elementSegmentsDropped.toList()
    private val segmentBytes: List<ByteArray> = dataSegments.map(ByteArray::copyOf)

    public fun globals(): List<Value> = globalValues.map(::copySnapshotValue)
    public fun dataSegments(): List<ByteArray> = segmentBytes.map(ByteArray::copyOf)
}

/** A stable route from a function body to one nested structured-control body. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class RuntimeBodyStep(
    public val instructionIndex: Int,
    public val branch: RuntimeBodyBranch,
    public val branchIndex: Int = -1,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public enum class RuntimeBodyBranch {
    Body,
    Then,
    Else,
    Catch,
    CatchAll,
}

@io.heapy.kwasm.ExperimentalKwasmApi
public enum class RuntimeControlKind {
    Function,
    Block,
    Loop,
    If,
    TryTable,
    LegacyTry,
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeControlSnapshot(
    public val kind: RuntimeControlKind,
    bodyPath: List<RuntimeBodyStep>,
    public val pc: Int,
    public val stackBase: Int,
    public val parameterCount: Int,
    public val resultCount: Int,
    public val labelArity: Int,
    /** Legacy-EH exception retained by this control for `rethrow`. */
    public val caughtException: GuestException? = null,
) {
    public val bodyPath: List<RuntimeBodyStep> = bodyPath.toList()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeFrameSnapshot(
    public val functionIndex: Int,
    locals: List<Value>,
    public val stackBase: Int,
    controls: List<RuntimeControlSnapshot>,
) {
    private val localValues: List<Value> = locals.map(::copySnapshotValue)
    public val controls: List<RuntimeControlSnapshot> = controls.map {
        RuntimeControlSnapshot(
            it.kind,
            it.bodyPath,
            it.pc,
            it.stackBase,
            it.parameterCount,
            it.resultCount,
            it.labelArity,
            it.caughtException,
        )
    }

    public fun locals(): List<Value> = localValues.map(::copySnapshotValue)
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimePendingImportSnapshot(
    public val functionIndex: Int,
    arguments: List<Value>,
) {
    private val argumentValues: List<Value> = arguments.map(::copySnapshotValue)
    public fun arguments(): List<Value> = argumentValues.map(::copySnapshotValue)
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class RuntimeStoreSnapshot(
    public val instance: RuntimeInstanceSnapshot,
    valueStack: List<Value>,
    frames: List<RuntimeFrameSnapshot>,
    public val pendingImport: RuntimePendingImportSnapshot?,
    public val fuel: Long,
    public val instructionsUntilCheckpoint: Int,
) {
    private val values: List<Value> = valueStack.map(::copySnapshotValue)
    public val frames: List<RuntimeFrameSnapshot> = frames.map {
        RuntimeFrameSnapshot(it.functionIndex, it.locals(), it.stackBase, it.controls)
    }

    public fun valueStack(): List<Value> = values.map(::copySnapshotValue)
}

internal fun copySnapshotValue(value: Value): Value = when (value) {
    is Value.I32 -> value.copy()
    is Value.I64 -> value.copy()
    is Value.F32 -> value.copy()
    is Value.F64 -> value.copy()
    is Value.V128 -> Value.V128(value.bytes.copyOf())
    is Value.Ref.Func -> value.copy()
    is Value.Ref.Extern -> value.copy()
    is Value.Ref.Host -> Value.Ref.Host(value.value)
    is Value.Ref.I31 -> value.copy()
    is Value.Ref.Gc -> value.copy()
    is Value.Ref.Exn -> value.copy()
    is Value.Ref.AnyExtern -> value.copy()
}
