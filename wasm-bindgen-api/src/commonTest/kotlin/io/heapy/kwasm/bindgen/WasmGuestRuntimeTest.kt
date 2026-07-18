package io.heapy.kwasm.bindgen

import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WasmGuestRuntimeTest {
    @Test
    fun requestEnvelopeRoundTripsStrictRoutingAndArguments() {
        val arguments = WasmAbiCodec.encode(
            WasmAbiValue.Int32(7),
            WasmAbiValue.Utf8("hello"),
        )

        val encoded = WasmGuestRuntimeRequestCodec.encode(
            boundary = "test:guest",
            function = "greet",
            arguments = arguments,
        )
        val decoded = WasmGuestRuntimeRequestCodec.decode(encoded)

        assertEquals("test:guest", decoded.boundary)
        assertEquals("greet", decoded.function)
        assertContentEquals(arguments, decoded.arguments)
    }

    @Test
    fun registryDispatchesImmediateSuspendHandler() {
        WasmGuestExportRegistry.install(
            listOf(
                WasmExportBinding(
                    boundary = "test:registry:immediate",
                    function = "echo",
                    isSuspend = true,
                ) { arguments -> arguments + 42 },
            ),
        )

        assertContentEquals(
            byteArrayOf(1, 2, 42),
            WasmGuestExportRegistry.dispatchBlocking(
                boundary = "test:registry:immediate",
                function = "echo",
                arguments = byteArrayOf(1, 2),
            ),
        )
    }

    @Test
    fun registryRejectsGuestImplementationThatActuallySuspends() {
        WasmGuestExportRegistry.install(
            listOf(
                WasmExportBinding(
                    boundary = "test:registry:suspends",
                    function = "wait",
                    isSuspend = true,
                ) {
                    suspendCoroutine<ByteArray> { }
                },
            ),
        )

        assertFailsWith<WasmGuestSuspensionNotSupportedException> {
            WasmGuestExportRegistry.dispatchBlocking(
                boundary = "test:registry:suspends",
                function = "wait",
                arguments = byteArrayOf(),
            )
        }
    }

    @Test
    fun decoderRejectsNonCanonicalEnvelope() {
        val encoded = WasmGuestRuntimeRequestCodec.encode(
            boundary = "test:guest",
            function = "greet",
            arguments = byteArrayOf(),
        )
        encoded[5] = 1

        assertFailsWith<WasmGuestRuntimeProtocolException> {
            WasmGuestRuntimeRequestCodec.decode(encoded)
        }
    }
}
