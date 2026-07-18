package io.heapy.kwasm.tck

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfiguredWasiTestsuiteNativeTest {
    @Test
    fun configuredOfficialPreview1Corpus() = runBlocking {
        runConfiguredWasiTestsuite()
    }
}

private suspend fun runConfiguredWasiTestsuite() {
    val root = WasiTestsuitePlatform.configuredSuiteRoot() ?: return
    val cases = WasiTestsuitePlatform.loadPreview1Cases(root)
    assertTrue(
        cases.isNotEmpty(),
        "No wasm32-wasip1 .wasm fixtures found under configured wasi-testsuite root '$root'",
    )
    val exclusions = WasiTestsuiteExclusions.parse(
        WasiTestsuitePlatform.checkedInExclusions(),
    )
    exclusions.requireAllMatch(cases.map(WasiTestsuiteCase::id))
    val runner = WasiTestsuiteRunner(exclusions)
    val report = WasiTestsuiteReport(cases.map { runner.run(it) })
    println(report.description())
    report.requireSuccess()
}
