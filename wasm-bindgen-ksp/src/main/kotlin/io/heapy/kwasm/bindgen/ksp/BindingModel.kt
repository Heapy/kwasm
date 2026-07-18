package io.heapy.kwasm.bindgen.ksp

internal data class BoundaryModel(
    val packageName: String,
    val interfaceName: String,
    val qualifiedName: String,
    val wireName: String,
    val functions: List<FunctionModel>,
) {
    val generatedBaseName: String = interfaceName + "WasmBinding"
}

internal data class FunctionModel(
    val kotlinName: String,
    val wireName: String,
    val isSuspend: Boolean,
    val parameters: List<ParameterModel>,
    val returnType: BoundaryKotlinType,
)

internal data class ParameterModel(
    val name: String,
    val type: BoundaryKotlinType,
)

internal sealed interface BoundaryKotlinType {
    val kotlinSource: String
    val abiEnumName: String
}

internal enum class AbiKotlinType(
    val qualifiedName: String,
    override val kotlinSource: String,
    override val abiEnumName: String,
    val valueClassName: String?,
    val argumentAccessor: String?,
) : BoundaryKotlinType {
    UNIT(
        qualifiedName = "kotlin.Unit",
        kotlinSource = "kotlin.Unit",
        abiEnumName = "UNIT",
        valueClassName = null,
        argumentAccessor = null,
    ),
    INT(
        qualifiedName = "kotlin.Int",
        kotlinSource = "kotlin.Int",
        abiEnumName = "INT32",
        valueClassName = "Int32",
        argumentAccessor = "int32",
    ),
    LONG(
        qualifiedName = "kotlin.Long",
        kotlinSource = "kotlin.Long",
        abiEnumName = "INT64",
        valueClassName = "Int64",
        argumentAccessor = "int64",
    ),
    FLOAT(
        qualifiedName = "kotlin.Float",
        kotlinSource = "kotlin.Float",
        abiEnumName = "FLOAT32",
        valueClassName = "Float32",
        argumentAccessor = "float32",
    ),
    DOUBLE(
        qualifiedName = "kotlin.Double",
        kotlinSource = "kotlin.Double",
        abiEnumName = "FLOAT64",
        valueClassName = "Float64",
        argumentAccessor = "float64",
    ),
    BOOLEAN(
        qualifiedName = "kotlin.Boolean",
        kotlinSource = "kotlin.Boolean",
        abiEnumName = "BOOLEAN",
        valueClassName = "Bool",
        argumentAccessor = "boolean",
    ),
    STRING(
        qualifiedName = "kotlin.String",
        kotlinSource = "kotlin.String",
        abiEnumName = "STRING",
        valueClassName = "Utf8",
        argumentAccessor = "string",
    ),
    BYTE_ARRAY(
        qualifiedName = "kotlin.ByteArray",
        kotlinSource = "kotlin.ByteArray",
        abiEnumName = "BYTE_ARRAY",
        valueClassName = "Bytes",
        argumentAccessor = "byteArray",
    ),
    ;

    companion object {
        fun fromQualifiedName(qualifiedName: String): AbiKotlinType? =
            entries.firstOrNull { it.qualifiedName == qualifiedName }
    }
}

internal data class CompositeKotlinType(
    val qualifiedName: String,
) : BoundaryKotlinType {
    override val kotlinSource: String = qualifiedName
    override val abiEnumName: String = "COMPOSITE"
}
