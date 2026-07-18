package io.heapy.kwasm

/**
 * In-memory representation of a decoded WebAssembly module.
 *
 * A [Module] can only be created by [decode], which performs binary decoding
 * followed by the pure validation phase. Its collections are immutable and the
 * exact original bytes are retained for snapshot module-hash binding.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class Module internal constructor(
    /** Flattened unified type index space (function, struct, and array types). */
    types: List<DefinedType>,
    /** Recursive groups as ranges into [types]. */
    recursionGroups: List<RecursionGroup>,
    imports: List<Import>,
    functions: List<Function>,
    tables: List<Table>,
    memories: List<Memory>,
    globals: List<Global>,
    tags: List<Tag>,
    exports: List<Export>,
    public val startFunction: Int?,
    elementSegments: List<ElementSegment>,
    dataSegments: List<DataSegment>,
    public val dataCount: Int?,
    customSections: List<CustomSection>,
    public val encodedSizeBytes: Int,
    originalBytes: ByteArray,
) {
    public val types: List<DefinedType> = types.map(::freezeDefinedType).frozen()
    public val recursionGroups: List<RecursionGroup> = recursionGroups.frozen()
    public val imports: List<Import> = imports.frozen()
    public val functions: List<Function> = functions.map {
        it.copy(
            locals = it.locals.frozen(),
            body = freezeInstructions(it.body),
        )
    }.frozen()
    public val tables: List<Table> = tables.map {
        it.copy(init = freezeInstructions(it.init))
    }.frozen()
    public val memories: List<Memory> = memories.frozen()
    public val globals: List<Global> = globals.map {
        it.copy(init = freezeInstructions(it.init))
    }.frozen()
    public val tags: List<Tag> = tags.frozen()
    public val exports: List<Export> = exports.frozen()
    public val elementSegments: List<ElementSegment> =
        elementSegments.map(::freezeElementSegment).frozen()
    public val dataSegments: List<DataSegment> =
        dataSegments.map(::freezeDataSegment).frozen()
    public val customSections: List<CustomSection> = customSections.map {
        CustomSection(it.name, it.rawBytes)
    }.frozen()
    private val encodedModule: ByteArray = originalBytes.copyOf()

    /** A defensive copy of the exact bytes used to create this module. */
    public fun encodedBytes(): ByteArray = encodedModule.copyOf()

    /**
     * Lazily decoded standard `name` custom section. Malformed name-section
     * payloads are ignored because custom-section contents are non-normative.
     */
    public val nameSection: NameSection? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        customSections.firstOrNull { it.name == "name" }?.decodeNameSectionOrNull()
    }

    /** Number of imported functions (prepending the local function index space). */
    public val importedFunctionCount: Int
        get() = imports.count { it.desc is ImportDesc.Function }

    public val importedTableCount: Int get() = imports.count { it.desc is ImportDesc.Table }
    public val importedMemoryCount: Int get() = imports.count { it.desc is ImportDesc.Memory }
    public val importedGlobalCount: Int get() = imports.count { it.desc is ImportDesc.Global }
    public val importedTagCount: Int get() = imports.count { it.desc is ImportDesc.Tag }

    /** Type index for a function by absolute function index (imports + locals). */
    public fun functionTypeIndex(absFuncIndex: Int): Int {
        require(absFuncIndex >= 0) { "negative function index $absFuncIndex" }
        var importedIndex = 0
        for (import in imports) {
            val function = import.desc as? ImportDesc.Function ?: continue
            if (importedIndex == absFuncIndex) return function.typeIndex
            importedIndex++
        }
        return functions[absFuncIndex - importedIndex].typeIndex
    }

    public fun definedType(typeIndex: Int): DefinedType =
        types.getOrNull(typeIndex)
            ?: throw IndexOutOfBoundsException("unknown type index $typeIndex (size ${types.size})")

    public fun functionTypeByTypeIndex(typeIndex: Int): FuncType =
        definedType(typeIndex) as? FuncType
            ?: throw IllegalArgumentException("type index $typeIndex is not a function type")

    public fun functionType(absFuncIndex: Int): FuncType =
        functionTypeByTypeIndex(functionTypeIndex(absFuncIndex))

    public companion object {
        /** Decode and validate a WebAssembly binary module. */
        public fun decode(
            bytes: ByteArray,
            validationLimits: ModuleValidationLimits = ModuleValidationLimits(),
        ): Module = ModuleDecoder.decode(bytes, validationLimits)
    }
}

internal class ModuleBuilder {
    val types: MutableList<DefinedType> = mutableListOf()
    val recursionGroups: MutableList<RecursionGroup> = mutableListOf()
    val imports: MutableList<Import> = mutableListOf()
    val functions: MutableList<Function> = mutableListOf()
    val tables: MutableList<Table> = mutableListOf()
    val memories: MutableList<Memory> = mutableListOf()
    val globals: MutableList<Global> = mutableListOf()
    val tags: MutableList<Tag> = mutableListOf()
    val exports: MutableList<Export> = mutableListOf()
    var startFunction: Int? = null
    val elementSegments: MutableList<ElementSegment> = mutableListOf()
    val dataSegments: MutableList<DataSegment> = mutableListOf()
    var dataCount: Int? = null
    val customSections: MutableList<CustomSection> = mutableListOf()

    fun build(bytes: ByteArray): Module =
        Module(
            types = types.toList(),
            recursionGroups = recursionGroups.toList(),
            imports = imports.toList(),
            functions = functions.toList(),
            tables = tables.toList(),
            memories = memories.toList(),
            globals = globals.toList(),
            tags = tags.toList(),
            exports = exports.toList(),
            startFunction = startFunction,
            elementSegments = elementSegments.toList(),
            dataSegments = dataSegments.toList(),
            dataCount = dataCount,
            customSections = customSections.toList(),
            encodedSizeBytes = bytes.size,
            originalBytes = bytes,
        )
}

private fun CustomSection.decodeNameSectionOrNull(): NameSection? {
    return try {
        val reader = ByteReader(rawBytes)
        var moduleName: String? = null
        val functionNames = mutableMapOf<Int, String>()
        val localNames = mutableMapOf<Int, MutableMap<Int, String>>()
        while (!reader.isEof()) {
            val kind = reader.readByte().toInt() and 0xFF
            val size = reader.readSize("name subsection size", reader.remaining)
            val subsection = reader.readReader(size)
            when (kind) {
                0 -> moduleName = subsection.readName()
                1 -> {
                    val count = subsection.readSize("function name count", subsection.remaining)
                    repeat(count) {
                        functionNames[subsection.readU32().toInt()] = subsection.readName()
                    }
                }
                2 -> {
                    val functionCount = subsection.readSize("local-name function count", subsection.remaining)
                    repeat(functionCount) {
                        val functionIndex = subsection.readU32().toInt()
                        val localCount = subsection.readSize("local name count", subsection.remaining)
                        val names = localNames.getOrPut(functionIndex) { mutableMapOf() }
                        repeat(localCount) {
                            names[subsection.readU32().toInt()] = subsection.readName()
                        }
                    }
                }
            }
            if (!subsection.isEof()) subsection.readBytes(subsection.remaining)
        }
        NameSection(moduleName, functionNames.toMap(), localNames.mapValues { it.value.toMap() })
    } catch (_: WasmDecodeException) {
        null
    }
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Import(
    public val module: String,
    public val field: String,
    public val desc: ImportDesc,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class ImportDesc {
    public data class Function(public val typeIndex: Int) : ImportDesc()
    public data class Table(public val type: TableType) : ImportDesc()
    public data class Memory(public val type: MemoryType) : ImportDesc()
    public data class Global(public val type: GlobalType) : ImportDesc()
    public data class Tag(public val typeIndex: Int, public val attribute: Int) : ImportDesc()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Function(
    public val typeIndex: Int,
    public val locals: List<ValType>,
    public val body: List<Instr>,
    public val encodedBodySizeBytes: Int = 0,
) {
    /** Flattened locals (for execution): declared params are not included here. */
    public val localSlots: List<ValType> get() = locals
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Table(
    public val type: TableType,
    public val init: List<Instr> = listOf(Instr.RefNull(type.element.heap)),
)

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Memory(
    public val type: MemoryType,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Global(
    public val type: GlobalType,
    public val init: List<Instr>,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Tag(
    public val typeIndex: Int,
    public val attribute: Int = 0,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public data class Export(
    public val name: String,
    public val desc: ExportDesc,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class ExportDesc {
    public data class Function(public val index: Int) : ExportDesc()
    public data class Table(public val index: Int) : ExportDesc()
    public data class Memory(public val index: Int) : ExportDesc()
    public data class Global(public val index: Int) : ExportDesc()
    public data class Tag(public val index: Int) : ExportDesc()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class ElementSegment(
    public val tableIndex: Int,
    public val offset: List<Instr>?,
    public val mode: ElementMode,
    public val exprs: List<List<Instr>>,
    public val activeType: RefType? = null,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class ElementMode {
    public data class Active(public val tableIndex: Int, public val offset: List<Instr>) : ElementMode()
    public data class Passive(public val type: RefType) : ElementMode()
    public data class Declarative(public val type: RefType) : ElementMode()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class DataSegment(
    public val mode: DataMode,
    init: ByteArray,
) {
    internal val rawInit: ByteArray = init.copyOf()
    public val init: ByteArray get() = rawInit.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DataSegment && mode == other.mode && rawInit.contentEquals(other.rawInit)

    override fun hashCode(): Int = 31 * mode.hashCode() + rawInit.contentHashCode()

    internal fun dropped(): DataSegment = DataSegment(mode, ByteArray(0))
}

@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class DataMode {
    public data class Active(public val memoryIndex: Int, public val offset: List<Instr>) : DataMode()
    public object Passive : DataMode()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class CustomSection(public val name: String, bytes: ByteArray) {
    internal val rawBytes: ByteArray = bytes.copyOf()
    public val bytes: ByteArray get() = rawBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CustomSection && name == other.name && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = 31 * name.hashCode() + rawBytes.contentHashCode()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class NameSection(
    public val moduleName: String? = null,
    public val functionNames: Map<Int, String> = emptyMap(),
    public val localNames: Map<Int, Map<Int, String>> = emptyMap(),
)
