package io.heapy.kwasm.bindgen.ksp

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier

internal class BoundaryExtractor(private val logger: KSPLogger) {
    fun extract(declaration: KSClassDeclaration): BoundaryModel? {
        var valid = true

        fun report(message: String, symbol: KSAnnotated = declaration) {
            logger.error("[kwasm-bindgen] $message", symbol)
            valid = false
        }

        if (declaration.classKind != ClassKind.INTERFACE) {
            report("@WasmBoundary can only annotate an interface")
        }
        if (!declaration.isPublic()) {
            report("boundary interface must be public")
        }
        if (declaration.parentDeclaration != null) {
            report("boundary interface must be top-level")
        }
        if (declaration.typeParameters.isNotEmpty()) {
            report("boundary interface must not declare type parameters")
        }
        val inheritedTypes = declaration.superTypes
            .map { it.resolve() }
            .filterNot {
                it.declaration.qualifiedName?.asString() == "kotlin.Any"
            }
            .toList()
        if (inheritedTypes.isNotEmpty()) {
            report(
                "boundary interface inheritance is not supported yet; " +
                    "declare all boundary functions directly",
            )
        }

        val properties = declaration.declarations.filterIsInstance<KSPropertyDeclaration>().toList()
        properties.forEach {
            report("boundary properties are not supported; use functions", it)
        }

        val functions = declaration.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filterNot { it.simpleName.asString() == "<init>" }
            .toList()

        val functionModels = functions.mapNotNull { function ->
            extractFunction(function, ::report)
        }

        val duplicateWireNames = functionModels
            .groupBy(FunctionModel::wireName)
            .filterValues { it.size > 1 }
            .keys
        duplicateWireNames.forEach { wireName ->
            report(
                "function wire name '$wireName' is not unique; " +
                    "use @WasmExport(name = ...) to disambiguate overloads",
            )
        }

        if (!valid || functionModels.size != functions.size) return null

        val interfaceName = declaration.simpleName.asString()
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            report("boundary interface must have a qualified name")
            return null
        }

        val annotation = declaration.findAnnotation(WASM_BOUNDARY_ANNOTATION)
        val configuredName = annotation?.stringArgument("name").orEmpty()
        val wireName = configuredName.ifEmpty { qualifiedName }
        if ('\u0000' in wireName) {
            report("boundary wire name must not contain a NUL character")
            return null
        }

        return BoundaryModel(
            packageName = declaration.packageName.asString(),
            interfaceName = interfaceName,
            qualifiedName = qualifiedName,
            wireName = wireName,
            functions = functionModels,
        )
    }

    private fun extractFunction(
        function: KSFunctionDeclaration,
        report: (String, KSAnnotated) -> Unit,
    ): FunctionModel? {
        var valid = true

        fun functionError(message: String, symbol: KSAnnotated = function) {
            report("function '${function.simpleName.asString()}': $message", symbol)
            valid = false
        }

        if (!function.isPublic()) {
            functionError("must be public")
        }
        if (!function.isAbstract) {
            functionError("must be abstract; default implementations are not IDL")
        }
        if (function.extensionReceiver != null) {
            functionError("extension receivers are not supported")
        }
        if (function.typeParameters.isNotEmpty()) {
            functionError("type parameters are not supported")
        }

        val parameters = function.parameters.mapIndexedNotNull { index, parameter ->
            if (parameter.isVararg) {
                functionError("vararg parameters are not supported", parameter)
            }
            val name = parameter.name?.asString()
            if (name == null) {
                functionError("parameter $index must have a name", parameter)
                null
            } else {
                val type = resolveType(
                    reference = parameter.type,
                    allowUnit = false,
                    description = "parameter '$name'",
                    report = { functionError(it, parameter) },
                )
                type?.let { ParameterModel(name = name, type = it) }
            }
        }

        val returnType = function.returnType?.let {
            resolveType(
                reference = it,
                allowUnit = true,
                description = "return type",
                report = ::functionError,
            )
        } ?: run {
            functionError("return type could not be resolved")
            null
        }

        val kotlinName = function.simpleName.asString()
        val export = function.findAnnotation(WASM_EXPORT_ANNOTATION)
        val configuredWireName = export?.stringArgument("name").orEmpty()
        val wireName = configuredWireName.ifEmpty { kotlinName }
        if ('\u0000' in wireName) {
            functionError("wire name must not contain a NUL character")
        }

        if (!valid || parameters.size != function.parameters.size || returnType == null) {
            return null
        }
        return FunctionModel(
            kotlinName = kotlinName,
            wireName = wireName,
            isSuspend = Modifier.SUSPEND in function.modifiers,
            parameters = parameters,
            returnType = returnType,
        )
    }

    private fun resolveType(
        reference: KSTypeReference,
        allowUnit: Boolean,
        description: String,
        report: (String) -> Unit,
    ): BoundaryKotlinType? {
        val type = reference.resolve()
        if (type.isError) {
            report("$description is unresolved")
            return null
        }
        if (type.isMarkedNullable) {
            report("$description must be non-null")
            return null
        }
        if (type.arguments.isNotEmpty()) {
            report("$description must not have type arguments")
            return null
        }

        val qualifiedName = type.declaration.qualifiedName?.asString()
        val scalar = qualifiedName?.let(AbiKotlinType::fromQualifiedName)
        if (scalar != null && (scalar != AbiKotlinType.UNIT || allowUnit)) {
            return scalar
        }

        val declaration = type.declaration as? KSClassDeclaration
        if (
            qualifiedName != null &&
            declaration != null &&
            isSupportedComposite(declaration, mutableSetOf(), report)
        ) {
            return CompositeKotlinType(qualifiedName)
        }

        if (scalar == null || scalar == AbiKotlinType.UNIT && !allowUnit) {
            report(
                "$description has unsupported type '${reference}'; supported types are " +
                    "Int, Long, Float, Double, Boolean, String, ByteArray" +
                    ", @Serializable data classes, and @Serializable sealed hierarchies" +
                    if (allowUnit) "; Unit is also allowed as a return" else "",
            )
        }
        return null
    }

    private fun isSupportedComposite(
        declaration: KSClassDeclaration,
        visiting: MutableSet<String>,
        report: (String) -> Unit,
    ): Boolean {
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            report("composite boundary types must have a qualified name")
            return false
        }
        if (!declaration.isPublic()) {
            report("composite type '$qualifiedName' must be public")
            return false
        }
        if (declaration.typeParameters.isNotEmpty()) {
            report("composite type '$qualifiedName' must not declare type parameters")
            return false
        }
        if (declaration.findAnnotation(SERIALIZABLE_ANNOTATION) == null) {
            report("composite type '$qualifiedName' must be annotated @Serializable")
            return false
        }
        if (!visiting.add(qualifiedName)) return true

        val result =
            when {
                Modifier.DATA in declaration.modifiers ->
                    declaration.primaryConstructor
                        ?.parameters
                        .orEmpty()
                        .all { parameter ->
                            isSupportedCompositeMember(
                                parameter.type,
                                "$qualifiedName.${parameter.name?.asString() ?: "<unnamed>"}",
                                visiting,
                                report,
                            )
                        }
                Modifier.SEALED in declaration.modifiers -> {
                    val subclasses = declaration.getSealedSubclasses().toList()
                    if (subclasses.isEmpty()) {
                        report("sealed composite type '$qualifiedName' has no subclasses")
                        false
                    } else {
                        subclasses.all { subclass ->
                            isSupportedComposite(subclass, visiting, report)
                        }
                    }
                }
                else -> {
                    report(
                        "composite type '$qualifiedName' must be a data class or sealed class/interface",
                    )
                    false
                }
            }
        visiting.remove(qualifiedName)
        return result
    }

    private fun isSupportedCompositeMember(
        reference: KSTypeReference,
        description: String,
        visiting: MutableSet<String>,
        report: (String) -> Unit,
    ): Boolean {
        val type = reference.resolve()
        if (type.isError) {
            report("composite member '$description' is unresolved")
            return false
        }
        if (type.arguments.isNotEmpty()) {
            report("composite member '$description' must not have type arguments")
            return false
        }
        val qualifiedName = type.declaration.qualifiedName?.asString()
        val scalar = qualifiedName?.let(AbiKotlinType::fromQualifiedName)
        if (scalar != null && scalar != AbiKotlinType.UNIT) return true
        val declaration = type.declaration as? KSClassDeclaration
        if (declaration != null && isSupportedComposite(declaration, visiting, report)) {
            return true
        }
        report(
            "composite member '$description' has unsupported type '$reference'",
        )
        return false
    }
}

internal fun KSAnnotated.findAnnotation(qualifiedName: String): KSAnnotation? =
    annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

private fun KSAnnotation.stringArgument(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String

internal const val WASM_BOUNDARY_ANNOTATION: String =
    "io.heapy.kwasm.bindgen.WasmBoundary"
internal const val WASM_EXPORT_ANNOTATION: String =
    "io.heapy.kwasm.bindgen.WasmExport"
internal const val SERIALIZABLE_ANNOTATION: String =
    "kotlinx.serialization.Serializable"
