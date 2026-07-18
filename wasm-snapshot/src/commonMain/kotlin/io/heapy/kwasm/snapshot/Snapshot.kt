package io.heapy.kwasm.snapshot

import io.heapy.kwasm.FuelExhaustionPolicy
import io.heapy.kwasm.CheckpointMode
import io.heapy.kwasm.HostSnapshotHooks
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.RuntimeHostSnapshot
import io.heapy.kwasm.RuntimeStoreSnapshot
import io.heapy.kwasm.SnapshotFormatException
import io.heapy.kwasm.SnapshotModuleMismatch
import io.heapy.kwasm.SnapshotStateException
import io.heapy.kwasm.Store
import io.heapy.kwasm.WASM_RUNTIME_VERSION

/** Allocation and structural ceilings applied before decoding untrusted bytes. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class SnapshotLimits(
    public val maxSnapshotBytes: Int = 1 shl 30,
    public val maxSectionBytes: Int = 1 shl 30,
    public val maxSections: Int = 64,
    public val maxMemories: Int = 1_000,
    public val maxTotalMemoryBytes: Long = 1L shl 32,
    public val maxTables: Int = 1_000,
    public val maxTableElements: Int = 10_000_000,
    public val maxGlobals: Int = 1_000_000,
    public val maxValues: Int = 1_048_576,
    public val maxFrames: Int = 65_536,
    public val maxControlsPerFrame: Int = 65_536,
    public val maxLocalsPerFrame: Int = 1_000_000,
    public val maxBodyPathDepth: Int = 65_536,
    public val maxSegments: Int = 1_000_000,
    public val maxBlobBytes: Int = 256 * 1024 * 1024,
    public val maxExternKeyBytes: Int = 1 * 1024 * 1024,
    public val maxHostParticipants: Int = 64,
    public val maxHostParticipantIdBytes: Int = 1024,
    public val maxHostParticipantStateBytes: Int = 64 * 1024 * 1024,
    public val maxRuntimeVersionBytes: Int = 256,
    public val maxHeapObjects: Int = 1_000_000,
    public val maxHeapValues: Int = 10_000_000,
    public val maxReferenceNesting: Int = 256,
) {
    init {
        require(maxSnapshotBytes > 0)
        require(maxSectionBytes > 0)
        require(maxSections in 1..65_535)
        require(maxMemories >= 0)
        require(maxTotalMemoryBytes >= 0)
        require(maxTables >= 0)
        require(maxTableElements >= 0)
        require(maxGlobals >= 0)
        require(maxValues >= 0)
        require(maxFrames >= 0)
        require(maxControlsPerFrame >= 0)
        require(maxLocalsPerFrame >= 0)
        require(maxBodyPathDepth >= 0)
        require(maxSegments >= 0)
        require(maxHeapObjects >= 0)
        require(maxHeapValues >= 0)
        require(maxReferenceNesting >= 0)
        require(maxBlobBytes >= 0)
        require(maxExternKeyBytes >= 0)
        require(maxHostParticipants >= 0)
        require(maxHostParticipantIdBytes > 0)
        require(maxHostParticipantStateBytes >= 0)
        require(maxRuntimeVersionBytes > 0)
    }
}

/**
 * Converts opaque host references to portable keys and back.
 *
 * Returning `null` declines a reference. Snapshotting/restoration then fails
 * with a diagnostic naming every unresolved reference encountered.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface SnapshotHooks : HostSnapshotHooks {
    public fun externalizeExternRef(handle: Int): ByteArray?
    public fun rehydrateExternRef(key: ByteArray): Int?

    /**
     * Externalize an arbitrary non-null host object carried by `externref`.
     *
     * The default preserves compatibility for hosts that historically wrapped
     * integer handles in [io.heapy.kwasm.Value.Ref.Host].
     */
    public fun externalizeHostExternRef(value: Any): ByteArray? =
        (value as? Int)?.let(::externalizeExternRef)

    /**
     * Rehydrate a host object. Returning `null` declines the key and causes
     * restoration to fail before the target instance is mutated.
     */
    public fun rehydrateHostExternRef(key: ByteArray): Any? =
        rehydrateExternRef(key)
}

/** Header fields available without decoding the state sections. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class SnapshotHeader internal constructor(
    public val formatVersion: Int,
    public val runtimeVersion: String,
    public val featureFlags: ULong,
    moduleHash: ByteArray,
    public val sectionCount: Int,
) {
    private val hash: ByteArray = moduleHash.copyOf()
    public fun moduleHash(): ByteArray = hash.copyOf()
}

/** Versioned portable snapshot encoder and restorer. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object KwasmSnapshot {
    public const val FORMAT_VERSION: Int = 2

    /**
     * Capture [instance] at a defined suspension point.
     *
     * Snapshot mode requires canonical NaNs. Custom instruction-cost functions
     * are rejected because their semantics cannot be represented portably.
     * Host-state participants registered with the store are captured through
     * [hooks] in the same transaction.
     */
    public fun capture(
        instance: Instance,
        hooks: SnapshotHooks? = null,
        limits: SnapshotLimits = SnapshotLimits(),
    ): ByteArray {
        val store = instance.store
        requireSnapshotConfiguration(store)
        return store.captureSnapshotState(instance) { state ->
            SnapshotBinary.encode(instance, store, state, hooks, limits)
        }
    }

    /** Decode only the bounded header and section directory metadata. */
    public fun inspect(
        bytes: ByteArray,
        limits: SnapshotLimits = SnapshotLimits(),
    ): SnapshotHeader = SnapshotBinary.inspect(bytes, limits)

    /**
     * Restore into an already-created instance.
     *
     * The module hash and deterministic store configuration are checked before
     * runtime state is mutated.
     */
    public fun restore(
        bytes: ByteArray,
        instance: Instance,
        hooks: SnapshotHooks? = null,
        limits: SnapshotLimits = SnapshotLimits(),
    ): Instance {
        requireSnapshotConfiguration(instance.store)
        val decoded = SnapshotBinary.decode(bytes, hooks, limits)
        verifyCompatibility(decoded, instance.module, instance.store)
        instance.store.restoreSnapshotState(
            instance = instance,
            snapshot = decoded.state,
            hostSnapshots = decoded.hostStates,
            hooks = hooks,
        )
        return instance
    }

    /**
     * Instantiate [module] with [imports], then restore the captured state.
     *
     * Use the existing-instance overload when imported memories/tables/globals
     * must not be initialized before snapshot validation. Host modules such as
     * WASI that register snapshot participants during instance attachment also
     * require the existing-instance overload so the target participant exists
     * before restoration is prepared.
     */
    public fun restore(
        bytes: ByteArray,
        module: Module,
        store: Store,
        imports: ResolvedImports = ResolvedImports(),
        hooks: SnapshotHooks? = null,
        limits: SnapshotLimits = SnapshotLimits(),
    ): Instance {
        requireSnapshotConfiguration(store)
        val decoded = SnapshotBinary.decode(bytes, hooks, limits)
        verifyCompatibility(decoded, module, store)
        if (decoded.hostStates.isNotEmpty()) {
            throw SnapshotStateException(
                "snapshot contains host-participant state; instantiate and attach host " +
                    "modules, then use the existing-instance restore overload",
            )
        }
        return store.instantiateAndRestoreSnapshot(
            module = module,
            imports = imports,
            snapshot = decoded.state,
        )
    }

    private fun requireSnapshotConfiguration(store: Store) {
        if (store.config.checkpointMode != CheckpointMode.Enabled) {
            throw SnapshotStateException(
                "checkpointMode=Enabled is required for snapshots",
            )
        }
        if (!store.config.canonicalizeNaNs) {
            throw SnapshotStateException(
                "canonicalizeNaNs=true is required for portable snapshots",
            )
        }
        if (store.config.instructionCosts != null) {
            throw SnapshotStateException(
                "custom instruction costs have no portable snapshot identity",
            )
        }
    }

    private fun verifyCompatibility(
        decoded: DecodedSnapshot,
        module: Module,
        store: Store,
    ) {
        if (decoded.header.runtimeVersion != WASM_RUNTIME_VERSION) {
            throw SnapshotFormatException(
                "snapshot runtime ${decoded.header.runtimeVersion} is incompatible with " +
                    "runtime $WASM_RUNTIME_VERSION",
            )
        }
        val actualHash = sha256(module.encodedBytes())
        val expectedHash = decoded.header.moduleHash()
        if (!expectedHash.contentEquals(actualHash)) {
            throw SnapshotModuleMismatch(expectedHash, actualHash)
        }
        val stored = decoded.configuration
        val actual = store.config
        if (
            stored.maxFrames != actual.limits.maxFrames ||
            stored.maxValueStackSlots != actual.limits.maxValueStackSlots ||
            stored.checkpointInterval != actual.checkpointInterval ||
            stored.fuelEnabled != actual.fuelEnabled ||
            stored.fuelExhaustionPolicy != actual.fuelExhaustionPolicy
        ) {
            throw SnapshotStateException(
                "store configuration differs from snapshot: " +
                    "limits/checkpoint/fuel policy must match exactly",
            )
        }
    }
}

internal data class SnapshotConfiguration(
    val maxFrames: Int,
    val maxValueStackSlots: Int,
    val checkpointInterval: Int,
    val fuelEnabled: Boolean,
    val fuelExhaustionPolicy: FuelExhaustionPolicy,
)

internal data class DecodedSnapshot(
    val header: SnapshotHeader,
    val configuration: SnapshotConfiguration,
    val state: RuntimeStoreSnapshot,
    val hostStates: List<RuntimeHostSnapshot>,
)
