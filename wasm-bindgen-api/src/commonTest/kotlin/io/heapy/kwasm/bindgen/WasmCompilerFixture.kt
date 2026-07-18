package io.heapy.kwasm.bindgen

import kotlinx.serialization.Serializable

@Serializable
public data class WasmCompilerProfile(
    public val name: String,
    public val score: Int,
)

@WasmBoundary(name = "test:wasm-compiler")
public interface WasmCompilerFixture {
    public fun add(left: Int, right: Int): Int

    public suspend fun echo(value: String): String

    public fun profile(value: WasmCompilerProfile): WasmCompilerProfile
}
