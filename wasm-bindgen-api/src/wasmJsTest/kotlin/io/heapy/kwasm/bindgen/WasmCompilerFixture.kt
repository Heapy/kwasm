package io.heapy.kwasm.bindgen

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
