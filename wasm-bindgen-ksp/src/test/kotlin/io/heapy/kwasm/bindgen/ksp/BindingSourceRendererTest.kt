package io.heapy.kwasm.bindgen.ksp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BindingSourceRendererTest {
    @Test
    fun rendersTypedHostAndGuestAdapters() {
        val model = BoundaryModel(
            packageName = "sample",
            interfaceName = "Greeter",
            qualifiedName = "sample.Greeter",
            wireName = "demo:greeter",
            functions = listOf(
                FunctionModel(
                    kotlinName = "greet",
                    wireName = "say-hello",
                    isSuspend = false,
                    parameters = listOf(
                        ParameterModel("name", AbiKotlinType.STRING),
                        ParameterModel("excited", AbiKotlinType.BOOLEAN),
                    ),
                    returnType = AbiKotlinType.STRING,
                ),
                FunctionModel(
                    kotlinName = "store",
                    wireName = "store",
                    isSuspend = true,
                    parameters = listOf(ParameterModel("bytes", AbiKotlinType.BYTE_ARRAY)),
                    returnType = AbiKotlinType.UNIT,
                ),
                FunctionModel(
                    kotlinName = "profile",
                    wireName = "profile",
                    isSuspend = false,
                    parameters = listOf(
                        ParameterModel(
                            "profile",
                            CompositeKotlinType("sample.Profile"),
                        ),
                    ),
                    returnType = CompositeKotlinType("sample.Profile"),
                ),
            ),
        )

        val source = BindingSourceRenderer().render(model)

        assertContains(
            source,
            "@file:OptIn(io.heapy.kwasm.ExperimentalKwasmApi::class)",
        )
        assertContains(source, "public object `GreeterWasmBinding`")
        assertContains(source, "public class `GreeterHostClient`")
        assertContains(source, "public class `GreeterHostImports`")
        assertContains(source, "public class `GreeterGuestClient`")
        assertContains(source, "public class `GreeterGuestExports`")
        assertContains(source, "invoker.invokeSuspend(")
        assertContains(source, "implementation.`store`(_kwasmArguments.byteArray(0))")
        assertContains(
            source,
            "WasmGuestExportRegistry.install(bindings())",
        )
        assertContains(source, "WasmAbiValue.Utf8(`name`)")
        assertContains(
            source,
            "WasmCompositeCodec.encode(sample.Profile.serializer(), `profile`)",
        )
        assertContains(
            source,
            "WasmCompositeCodec.decode(sample.Profile.serializer(), _kwasmArguments.composite(0))",
        )
        assertContains(source, "type = io.heapy.kwasm.bindgen.WasmAbiType.COMPOSITE")
        assertContains(source, "isSuspend = true")
        assertFalse(source.contains("TODO"))
    }

    @Test
    fun rendersWasmGuestClientWithCompilerTransportDefault() {
        val model = BoundaryModel(
            packageName = "sample",
            interfaceName = "Greeter",
            qualifiedName = "sample.Greeter",
            wireName = "demo:greeter",
            functions = emptyList(),
        )

        val source = BindingSourceRenderer(BindingTarget.WASM).render(model)

        assertContains(
            source,
            "io.heapy.kwasm.bindgen.generated.KwasmWasmGuestInvoker",
        )
        assertContains(source, "public fun install(): kotlin.Unit")
    }

    @Test
    fun rendersCompilerRecognizedWasmRuntimeAnnotations() {
        val source = WasmGuestRuntimeRenderer().render()

        assertContains(source, "import kotlin.wasm.WasmExport")
        assertContains(source, "import kotlin.wasm.WasmImport")
        assertContains(
            source,
            "@WasmExport(WasmGuestRuntimeAbi.ALLOCATE_EXPORT)",
        )
        assertContains(
            source,
            "@WasmExport(WasmGuestRuntimeAbi.FREE_EXPORT)",
        )
        assertContains(
            source,
            "@WasmExport(WasmGuestRuntimeAbi.INVOKE_EXPORT)",
        )
        assertContains(source, "private external fun kwasmBindgenHostBegin")
        assertContains(source, "private external fun kwasmBindgenHostFinish")
        assertContains(source, "WasmGuestExportRegistry.dispatchBlocking(")
    }

    @Test
    fun escapesWireNamesAsKotlinLiterals() {
        val model = BoundaryModel(
            packageName = "sample",
            interfaceName = "Dollar",
            qualifiedName = "sample.Dollar",
            wireName = "price\$\"quoted\"",
            functions = emptyList(),
        )

        val source = BindingSourceRenderer().render(model)

        assertContains(source, "\"price\\\$\\\"quoted\\\"\"")
        assertContains(source, "functions = emptyList()")
        assertContains(source, "bindings(): kotlin.collections.List")
    }

    @Test
    fun supportedTypeTableIsCompleteAndStable() {
        val expected = mapOf(
            "kotlin.Unit" to AbiKotlinType.UNIT,
            "kotlin.Int" to AbiKotlinType.INT,
            "kotlin.Long" to AbiKotlinType.LONG,
            "kotlin.Float" to AbiKotlinType.FLOAT,
            "kotlin.Double" to AbiKotlinType.DOUBLE,
            "kotlin.Boolean" to AbiKotlinType.BOOLEAN,
            "kotlin.String" to AbiKotlinType.STRING,
            "kotlin.ByteArray" to AbiKotlinType.BYTE_ARRAY,
        )

        expected.forEach { (qualifiedName, type) ->
            assertEquals(type, AbiKotlinType.fromQualifiedName(qualifiedName))
        }
        assertEquals(null, AbiKotlinType.fromQualifiedName("kotlin.Short"))
        assertTrue(AbiKotlinType.entries.all { it.abiEnumName.isNotBlank() })
    }

    @Test
    fun providerIsRegisteredForServiceLoading() {
        val path =
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
        val resource = assertNotNull(javaClass.classLoader.getResource(path))
        val registration = resource.readText()

        assertTrue(
            registration.lineSequence().any {
                it.trim() == "io.heapy.kwasm.bindgen.ksp.WasmBindgenProcessorProvider"
            },
        )
    }
}
