package io.heapy.kwasm.bindgen.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

public class WasmBindgenProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        WasmBindgenProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            target = environment.platforms.bindingTarget(),
        )
}

internal class WasmBindgenProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val target: BindingTarget,
) : SymbolProcessor {
    private val generatedTypes = mutableSetOf<String>()
    private val extractor = BoundaryExtractor(logger)
    private val renderer = BindingSourceRenderer(target)
    private var wasmRuntimeGenerated: Boolean = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation(WASM_BOUNDARY_ANNOTATION)
            .toList()
        val deferred = symbols.filterNot { it.validate() }

        symbols
            .filter { it.validate() }
            .forEach { symbol ->
                val declaration = symbol as? KSClassDeclaration
                if (declaration == null) {
                    logger.error(
                        "[kwasm-bindgen] @WasmBoundary can only annotate an interface",
                        symbol,
                    )
                    return@forEach
                }
                val qualifiedName = declaration.qualifiedName?.asString() ?: return@forEach
                if (qualifiedName in generatedTypes) return@forEach

                val model = extractor.extract(declaration) ?: return@forEach
                write(model, declaration)
                if (target == BindingTarget.WASM && !wasmRuntimeGenerated) {
                    writeWasmRuntime()
                    wasmRuntimeGenerated = true
                }
                generatedTypes += qualifiedName
            }

        return deferred
    }

    private fun write(model: BoundaryModel, declaration: KSClassDeclaration) {
        val dependencies = declaration.containingFile?.let {
            Dependencies(aggregating = false, it)
        } ?: Dependencies(aggregating = false)
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = model.packageName,
            fileName = model.interfaceName + "WasmBindings",
            extensionName = "kt",
        ).bufferedWriter().use { writer ->
            writer.write(renderer.render(model))
        }
    }

    private fun writeWasmRuntime() {
        codeGenerator.createNewFile(
            dependencies = Dependencies.ALL_FILES,
            packageName = "io.heapy.kwasm.bindgen.generated",
            fileName = "KwasmWasmGuestRuntime",
            extensionName = "kt",
        ).bufferedWriter().use { writer ->
            writer.write(WasmGuestRuntimeRenderer().render())
        }
    }
}

private fun List<com.google.devtools.ksp.processing.PlatformInfo>.bindingTarget(): BindingTarget =
    if (any { it.platformName.contains("wasm", ignoreCase = true) }) {
        BindingTarget.WASM
    } else {
        BindingTarget.PORTABLE
    }
