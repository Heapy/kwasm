package io.heapy.kwasm.bindgen

/**
 * Marks a public Kotlin interface as a kwasm interface-definition boundary.
 *
 * Every abstract member of the interface becomes a function in the generated
 * host and guest bindings. [name] is the stable wire name of the boundary. If
 * it is empty, bindgen uses the interface's fully-qualified Kotlin name.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@io.heapy.kwasm.ExperimentalKwasmApi
public annotation class WasmBoundary(
    public val name: String = "",
)

/**
 * Gives a boundary function a stable wire name.
 *
 * This is IDL metadata and is distinct from the compiler's
 * `kotlin.wasm.WasmExport`; bindgen places that platform annotation only on
 * generated top-level raw transport functions. An empty [name] means that the
 * Kotlin function name is used.
 */
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@io.heapy.kwasm.ExperimentalKwasmApi
public annotation class WasmExport(
    public val name: String = "",
)
