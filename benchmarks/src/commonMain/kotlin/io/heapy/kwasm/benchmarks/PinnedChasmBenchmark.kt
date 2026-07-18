package io.heapy.kwasm.benchmarks

import io.github.charlietap.chasm.embedding.dsl.imports
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.shapes.Instance
import io.github.charlietap.chasm.embedding.shapes.Store
import io.github.charlietap.chasm.embedding.shapes.expect
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.runtime.value.ExecutionValue
import io.github.charlietap.chasm.runtime.value.NumberValue
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlin.time.TimeSource

/**
 * Same-process comparison rows for the Chasm interpreter pinned by
 * `benchmarks/upstreams.lock.json`.
 *
 * The external comparison profile feeds Chasm the exact byte arrays and
 * arguments used by the kwasm rows. It is excluded from the ordinary
 * self-history profile.
 */
@State(Scope.Benchmark)
public open class PinnedChasmBenchmark {
    private lateinit var fib: ChasmGuest
    private lateinit var sha: ChasmGuest
    private lateinit var json: ChasmGuest
    private lateinit var coreMark: ChasmGuest

    @Setup
    public fun prepare() {
        fib = ChasmGuest(BenchmarkFixtures.fibModule)
        sha = ChasmGuest(BenchmarkFixtures.sha256LoopModule)
        json = ChasmGuest(BenchmarkFixtures.jsonModule)
        check(fib.invokeI32("fib", 20) == 6_765) {
            "Chasm fib fixture returned an unexpected result"
        }
        check(
            sha.invokeI32("sha256_loop", SHA_ROUNDS, SHA_SEED) ==
                EXPECTED_SHA256_RESULT,
        ) {
            "Chasm SHA-256 fixture returned an unexpected result"
        }
        check(
            json.invokeI32(
                "parse_json",
                BenchmarkFixtures.JSON_DOCUMENT.length,
            ) == EXPECTED_JSON_RESULT,
        ) {
            "Chasm JSON fixture returned an unexpected result"
        }
        val coreMarkPath = checkNotNull(PlatformBinary.environment("KWASM_COREMARK_WASM")) {
            "KWASM_COREMARK_WASM must name the checksum-pinned CoreMark fixture"
        }
        coreMark = ChasmGuest(
            bytes = PlatformBinary.read(coreMarkPath),
            clockImport = true,
        )
    }

    @Benchmark
    public open fun fib35(): Int =
        fib.invokeI32("fib", 35)

    @Benchmark
    public open fun sha256(): Int =
        sha.invokeI32("sha256_loop", SHA_ROUNDS, SHA_SEED)

    @Benchmark
    public open fun json(): Int =
        json.invokeI32("parse_json", BenchmarkFixtures.JSON_DOCUMENT.length)

    @Benchmark
    public open fun coreMark(): Int =
        coreMark.invoke("run").fold(1) { hash, value ->
            31 * hash + value.hashCode()
        }

    private companion object {
        const val SHA_ROUNDS: Int = 16_384
        const val SHA_SEED: Int = 0x6A09_E667
        const val EXPECTED_SHA256_RESULT: Int = -466_365_695
        const val EXPECTED_JSON_RESULT: Int = -656_560_826
    }
}

private class ChasmGuest(
    bytes: ByteArray,
    clockImport: Boolean = false,
) {
    private val store: Store = store()
    private val instance: Instance

    init {
        val clockOrigin = TimeSource.Monotonic.markNow()
        val resolvedImports =
            if (clockImport) {
                imports(store) {
                    function {
                        moduleName = "env"
                        entityName = "clock_ms"
                        type {
                            results { i64() }
                        }
                        reference {
                            listOf(
                                NumberValue.I64(
                                    clockOrigin.elapsedNow().inWholeMilliseconds,
                                ),
                            )
                        }
                    }
                }
            } else {
                emptyList()
            }
        val decoded = module(bytes).expect("Chasm could not decode benchmark module")
        instance = instance(store, decoded, resolvedImports)
            .expect("Chasm could not instantiate benchmark module")
    }

    fun invokeI32(
        export: String,
        vararg arguments: Int,
    ): Int {
        val result = invoke(
            export,
            arguments.map { NumberValue.I32(it) },
        ).single()
        return (result as NumberValue.I32).value
    }

    fun invoke(
        export: String,
        arguments: List<ExecutionValue> = emptyList(),
    ): List<ExecutionValue> =
        invoke(store, instance, export, arguments)
            .expect("Chasm benchmark invocation '$export' failed")
}
