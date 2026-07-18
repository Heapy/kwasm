@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)

package io.heapy.kwasm.bindgen

import kotlin.wasm.WasmExport

private object WasmCompilerFixtureImplementation : WasmCompilerFixture {
    override fun add(left: Int, right: Int): Int = left + right

    override suspend fun echo(value: String): String = value

    override fun profile(value: WasmCompilerProfile): WasmCompilerProfile =
        value.copy(score = value.score + 1)
}

public fun installWasmCompilerFixture() {
    WasmCompilerFixtureGuestExports(WasmCompilerFixtureImplementation).install()
}

public fun createWasmCompilerFixtureHostClient(): WasmCompilerFixtureGuestClient =
    WasmCompilerFixtureGuestClient()

/**
 * Compiler-output compatibility probe used by kwasm's JVM runtime test.
 *
 * Keeping the call behind a raw scalar export lets the same linked module run
 * under the normal Kotlin/Wasm test launcher, while the kwasm integration can
 * prove that the generated `begin`/`finish` imports cross real guest memory.
 */
@WasmExport("__kwasm_bindgen_host_round_trip_v1")
private fun kwasmBindgenHostRoundTrip(): Int =
    createWasmCompilerFixtureHostClient().add(19, 23)
