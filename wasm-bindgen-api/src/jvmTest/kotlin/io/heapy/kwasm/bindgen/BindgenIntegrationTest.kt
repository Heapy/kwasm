package io.heapy.kwasm.bindgen

import kotlinx.serialization.Serializable
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
public data class BindgenProfile(
    public val name: String,
    public val score: Int,
)

@Serializable
public sealed interface BindgenOutcome

@Serializable
public data class BindgenAccepted(public val profile: BindgenProfile) : BindgenOutcome

@Serializable
public data class BindgenRejected(public val reason: String) : BindgenOutcome

@WasmBoundary(name = "test:fixture")
public interface BindgenFixture {
    public fun add(left: Int, right: Int): Int

    @WasmExport(name = "echo-bytes")
    public suspend fun echo(bytes: ByteArray, enabled: Boolean): ByteArray

    public fun notify(message: String)

    public fun numeric(long: Long, float: Float, double: Double): Double

    public fun shadow(invoker: Int, _kwasmValues: String): String

    public fun profile(value: BindgenProfile): BindgenProfile

    public fun outcome(value: BindgenOutcome): BindgenOutcome
}

class BindgenIntegrationTest {
    private val implementation = object : BindgenFixture {
        override fun add(left: Int, right: Int): Int = left + right

        override suspend fun echo(bytes: ByteArray, enabled: Boolean): ByteArray =
            if (enabled) bytes else byteArrayOf()

        override fun notify(message: String) {
            notified = message
        }

        override fun numeric(long: Long, float: Float, double: Double): Double =
            long + float + double

        override fun shadow(invoker: Int, _kwasmValues: String): String =
            "$invoker:$_kwasmValues"

        override fun profile(value: BindgenProfile): BindgenProfile =
            value.copy(score = value.score + 1)

        override fun outcome(value: BindgenOutcome): BindgenOutcome =
            when (value) {
                is BindgenAccepted -> BindgenRejected("already accepted: ${value.profile.name}")
                is BindgenRejected -> BindgenAccepted(BindgenProfile(value.reason, 1))
            }
    }

    @Test
    fun generatedHostClientUsesTypedAbiAndSuspendPath() {
        var suspendPathUsed = false
        val client = BindgenFixtureHostClient(
            object : WasmHostInvoker {
                override fun invoke(
                    boundary: String,
                    function: String,
                    arguments: ByteArray,
                ): ByteArray {
                    assertEquals("test:fixture", boundary)
                    return when (function) {
                        "add" -> {
                            val values = WasmAbiArguments(WasmAbiCodec.decode(arguments))
                            values.requireSize(2)
                            WasmAbiCodec.encode(
                                WasmAbiValue.Int32(values.int32(0) + values.int32(1)),
                            )
                        }
                        "notify" -> WasmAbiCodec.encode()
                        "numeric" -> {
                            val values = WasmAbiArguments(WasmAbiCodec.decode(arguments))
                            WasmAbiCodec.encode(
                                WasmAbiValue.Float64(
                                    values.int64(0) + values.float32(1) + values.float64(2),
                                ),
                            )
                        }
                        "shadow" -> {
                            val values = WasmAbiArguments(WasmAbiCodec.decode(arguments))
                            WasmAbiCodec.encode(
                                WasmAbiValue.Utf8(
                                    "${values.int32(0)}:${values.string(1)}",
                                ),
                            )
                        }
                        "profile" -> {
                            val values = WasmAbiArguments(WasmAbiCodec.decode(arguments))
                            val profile = WasmCompositeCodec.decode(
                                BindgenProfile.serializer(),
                                values.composite(0),
                            )
                            WasmAbiCodec.encode(
                                WasmAbiValue.Composite(
                                    WasmCompositeCodec.encode(
                                        BindgenProfile.serializer(),
                                        profile.copy(score = profile.score + 1),
                                    ),
                                ),
                            )
                        }
                        "outcome" -> {
                            val values = WasmAbiArguments(WasmAbiCodec.decode(arguments))
                            val outcome = WasmCompositeCodec.decode(
                                BindgenOutcome.serializer(),
                                values.composite(0),
                            )
                            val result: BindgenOutcome =
                                when (outcome) {
                                    is BindgenAccepted -> BindgenRejected(outcome.profile.name)
                                    is BindgenRejected ->
                                        BindgenAccepted(BindgenProfile(outcome.reason, 1))
                                }
                            WasmAbiCodec.encode(
                                WasmAbiValue.Composite(
                                    WasmCompositeCodec.encode(
                                        BindgenOutcome.serializer(),
                                        result,
                                    ),
                                ),
                            )
                        }
                        else -> error("Unexpected synchronous function $function")
                    }
                }

                override suspend fun invokeSuspend(
                    boundary: String,
                    function: String,
                    arguments: ByteArray,
                ): ByteArray {
                    suspendPathUsed = true
                    assertEquals("test:fixture", boundary)
                    assertEquals("echo-bytes", function)
                    val values = WasmAbiArguments(WasmAbiCodec.decode(arguments))
                    values.requireSize(2)
                    assertTrue(values.boolean(1))
                    return WasmAbiCodec.encode(
                        WasmAbiValue.Bytes(values.byteArray(0)),
                    )
                }
            },
        )

        assertEquals(7, client.add(3, 4))
        client.notify("observed")
        assertEquals(15.5, client.numeric(10, 2.5f, 3.0))
        assertEquals("7:safe", client.shadow(7, "safe"))
        assertEquals(
            BindgenProfile("Ada", 8),
            client.profile(BindgenProfile("Ada", 7)),
        )
        assertEquals(
            BindgenRejected("Ada"),
            client.outcome(BindgenAccepted(BindgenProfile("Ada", 7))),
        )
        assertContentEquals(
            byteArrayOf(4, 5, 6),
            runImmediate { client.echo(byteArrayOf(4, 5, 6), enabled = true) },
        )
        assertTrue(suspendPathUsed)
    }

    @Test
    fun generatedImportAndExportRegistriesDispatchToImplementation() {
        val imports = BindgenFixtureHostImports(implementation).bindings()
        val addImport = imports.single { it.function == "add" }
        val addResult = runImmediate {
            addImport.invoke(
                WasmAbiCodec.encode(
                    WasmAbiValue.Int32(20),
                    WasmAbiValue.Int32(22),
                ),
            )
        }
        assertEquals(
            42,
            WasmAbiArguments(WasmAbiCodec.decode(addResult)).int32(0),
        )
        assertFalse(addImport.isSuspend)

        val exports = BindgenFixtureGuestExports(implementation).bindings()
        val echoExport = exports.single { it.function == "echo-bytes" }
        val echoResult = runImmediate {
            echoExport.invoke(
                WasmAbiCodec.encode(
                    WasmAbiValue.Bytes(byteArrayOf(9, 8)),
                    WasmAbiValue.Bool(true),
                ),
            )
        }
        assertContentEquals(
            byteArrayOf(9, 8),
            WasmAbiArguments(WasmAbiCodec.decode(echoResult)).byteArray(0),
        )
        assertTrue(echoExport.isSuspend)

        val profileExport = exports.single { it.function == "profile" }
        val profileResult = runImmediate {
            profileExport.invoke(
                WasmAbiCodec.encode(
                    WasmAbiValue.Composite(
                        WasmCompositeCodec.encode(
                            BindgenProfile.serializer(),
                            BindgenProfile("Grace", 9),
                        ),
                    ),
                ),
            )
        }
        assertEquals(
            BindgenProfile("Grace", 10),
            WasmCompositeCodec.decode(
                BindgenProfile.serializer(),
                WasmAbiArguments(WasmAbiCodec.decode(profileResult)).composite(0),
            ),
        )
    }

    @Test
    fun generatedDescriptorRetainsWireContract() {
        val descriptor = BindgenFixtureWasmBinding.descriptor

        assertEquals(WasmAbiCodec.VERSION, descriptor.abiVersion)
        assertEquals("test:fixture", descriptor.wireName)
        assertEquals(
            "echo-bytes",
            descriptor.functions.single { it.kotlinName == "echo" }.wireName,
        )
        assertEquals(
            WasmAbiType.COMPOSITE,
            descriptor.functions.single { it.kotlinName == "profile" }.returnType,
        )
    }

    private companion object {
        var notified: String? = null
    }
}

private fun <T> runImmediate(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) {
        "Test boundary unexpectedly suspended"
    }.getOrThrow()
}
