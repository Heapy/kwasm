package io.heapy.kwasm.bindgen

/**
 * Runtime-agnostic invocation seam used by generated host export clients.
 *
 * A kwasm runtime adapter implements this interface by resolving [boundary]
 * and [function] to an exported guest function and copying [arguments] through
 * guest memory. Suspend boundary functions use [invokeSuspend].
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface WasmHostInvoker {
    public fun invoke(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray

    public suspend fun invokeSuspend(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray = invoke(boundary, function, arguments)
}

/**
 * Blocking-style import seam used by generated Kotlin/Wasm guest clients.
 *
 * This intentionally has no suspend member: until guest-side async is
 * available, a `suspend` IDL member is emitted as a suspend Kotlin override
 * whose actual boundary invocation is blocking-style.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface WasmGuestInvoker {
    public fun invoke(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray
}

/** A host implementation function ready to be registered as a guest import. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmImportBinding(
    public val boundary: String,
    public val function: String,
    public val isSuspend: Boolean,
    private val handler: suspend (ByteArray) -> ByteArray,
) {
    public suspend fun invoke(arguments: ByteArray): ByteArray = handler(arguments)
}

/** A guest implementation function ready to be exposed as an export. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmExportBinding(
    public val boundary: String,
    public val function: String,
    public val isSuspend: Boolean,
    private val handler: suspend (ByteArray) -> ByteArray,
) {
    public suspend fun invoke(arguments: ByteArray): ByteArray = handler(arguments)
}

/** Stable metadata for a generated function parameter. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmParameterDescriptor(
    public val name: String,
    public val type: WasmAbiType,
)

/** Stable metadata for a generated boundary function. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmFunctionDescriptor(
    public val kotlinName: String,
    public val wireName: String,
    public val parameters: List<WasmParameterDescriptor>,
    public val returnType: WasmAbiType,
    public val isSuspend: Boolean,
)

/** Versioned metadata emitted beside every generated boundary. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmBoundaryDescriptor(
    public val kotlinType: String,
    public val wireName: String,
    public val abiVersion: Int,
    public val functions: List<WasmFunctionDescriptor>,
)
