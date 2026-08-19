package io.heapy.kwasm.benchmarks

import java.nio.file.Files
import java.nio.file.Path

/** Exports the generated canonical workload bytes for native runtime references. */
public fun main(arguments: Array<String>) {
    require(arguments.size == 1) {
        "usage: BenchmarkFixtureExporter OUTPUT_DIRECTORY"
    }
    val outputDirectory = Path.of(arguments.single()).toAbsolutePath().normalize()
    Files.createDirectories(outputDirectory)
    mapOf(
        "fib.wasm" to BenchmarkFixtures.fibModule,
        "sha256.wasm" to BenchmarkFixtures.sha256LoopModule,
        "json.wasm" to BenchmarkFixtures.jsonModule,
    ).forEach { (name, bytes) ->
        Files.write(outputDirectory.resolve(name), bytes)
    }
}
