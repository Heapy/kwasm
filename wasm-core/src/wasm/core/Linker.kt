package io.heapy.kwasm

/**
 * A runtime external value that can satisfy an import or be registered from an
 * instance export. Function and tag values retain their defining identity.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class ExternalValue {
    public data class Function(public val import: HostImport) : ExternalValue()
    public data class Memory(public val memory: MemoryInstance) : ExternalValue()
    public data class Table(public val table: TableInstance) : ExternalValue()
    public data class Global(public val global: GlobalInstance) : ExternalValue()
    public data class Tag(public val tag: TagInstance) : ExternalValue()
}

/** Nominal identity of a WebAssembly exception tag. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class TagInstance(
    public val type: FuncType,
    internal val typeContext: Module? = null,
)

/**
 * Name-based import linker. Definitions are keyed by the exact binary module
 * and field names and are type-checked before any local runtime state is
 * allocated.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class Linker {
    private val definitions: MutableMap<String, MutableMap<String, ExternalValue>> = mutableMapOf()

    public fun define(module: String, name: String, value: ExternalValue): Linker = apply {
        val namespace = definitions.getOrPut(module) { mutableMapOf() }
        if (namespace.put(name, value) != null) {
            throw LinkException("duplicate linker definition", module, name)
        }
    }

    public fun defineFunction(
        module: String,
        name: String,
        type: FuncType,
        function: HostFunction,
    ): Linker = define(module, name, ExternalValue.Function(HostImport(type, function)))

    /**
     * Define a function whose pointer arguments address memory owned by the
     * guest instance executing the import.
     */
    public fun defineCallerAwareFunction(
        module: String,
        name: String,
        type: FuncType,
        function: CallerAwareHostFunction,
    ): Linker = define(module, name, ExternalValue.Function(HostImport(type, function)))

    public fun defineMemory(module: String, name: String, memory: MemoryInstance): Linker =
        define(module, name, ExternalValue.Memory(memory))

    public fun defineTable(module: String, name: String, table: TableInstance): Linker =
        define(module, name, ExternalValue.Table(table))

    public fun defineGlobal(module: String, name: String, global: GlobalInstance): Linker =
        define(module, name, ExternalValue.Global(global))

    public fun defineTag(module: String, name: String, tag: TagInstance): Linker =
        define(module, name, ExternalValue.Tag(tag))

    /** Register every export of [instance] under [module]. */
    public fun defineInstance(
        module: String,
        instance: Instance,
        machine: Machine = Interpreter(),
    ): Linker = apply {
        instance.module.exports.forEach { export ->
            val value = when (val desc = export.desc) {
                is ExportDesc.Function -> ExternalValue.Function(
                    HostImport(
                        type = instance.functionType(desc.index),
                        guestAddress = GuestFunctionAddress(instance, desc.index),
                        typeContext = instance.functionTypeContext(desc.index),
                        fn = HostFunction { arguments ->
                            machine.invoke(instance, desc.index, arguments)
                        },
                    ),
                )
                is ExportDesc.Memory -> ExternalValue.Memory(instance.memories[desc.index])
                is ExportDesc.Table -> ExternalValue.Table(instance.tables[desc.index])
                is ExportDesc.Global -> ExternalValue.Global(instance.globals[desc.index])
                is ExportDesc.Tag -> ExternalValue.Tag(instance.tags[desc.index])
            }
            define(module, export.name, value)
        }
    }

    public fun instantiate(
        module: Module,
        store: Store = Store(),
    ): Instance {
        val functions = mutableListOf<HostImport>()
        val memories = mutableListOf<MemoryInstance>()
        val tables = mutableListOf<TableInstance>()
        val globals = mutableListOf<GlobalInstance>()
        val tags = mutableListOf<TagInstance>()

        module.imports.forEach { import ->
            val value = definitions[import.module]?.get(import.field)
                ?: throw LinkException("unknown import", import.module, import.field)
            when (val desc = import.desc) {
                is ImportDesc.Function -> {
                    val function = (value as? ExternalValue.Function)?.import
                        ?: wrongKind(import, "function")
                    val expected = module.functionTypeByTypeIndex(desc.typeIndex)
                    val actualModule = function.resolvedTypeContext()
                    if (!functionTypesEquivalent(module, expected, actualModule, function.type)) {
                        incompatible(import, "function type $expected", function.type.toString())
                    }
                    functions +=
                        if (
                            function.guestAddress != null &&
                            function.guestAddress.instance.store !== store
                        ) {
                            HostImport(
                                type = function.type,
                                guestAddress = null,
                                typeContext = actualModule,
                                fn = function.fn,
                            )
                        } else {
                            function
                        }
                }
                is ImportDesc.Memory -> {
                    val memory = (value as? ExternalValue.Memory)?.memory
                        ?: wrongKind(import, "memory")
                    checkMemoryImport(import, desc.type, memory)
                    memories += memory
                }
                is ImportDesc.Table -> {
                    val table = (value as? ExternalValue.Table)?.table
                        ?: wrongKind(import, "table")
                    checkTableImport(module, import, desc.type, table)
                    tables += table
                }
                is ImportDesc.Global -> {
                    val global = (value as? ExternalValue.Global)?.global
                        ?: wrongKind(import, "global")
                    val valueTypeMatches =
                        if (desc.type.mutability == Mutability.VAR) {
                            valueTypesEquivalent(
                                module,
                                desc.type.type,
                                global.typeContext,
                                global.type.type,
                            )
                        } else {
                            valueTypeSubtypeAcross(
                                global.typeContext,
                                global.type.type,
                                module,
                                desc.type.type,
                            )
                        }
                    if (
                        global.type.mutability != desc.type.mutability ||
                        !valueTypeMatches
                    ) {
                        incompatible(import, "global type ${desc.type}", global.type.toString())
                    }
                    globals += global
                }
                is ImportDesc.Tag -> {
                    val tag = (value as? ExternalValue.Tag)?.tag
                        ?: wrongKind(import, "tag")
                    val expected = module.functionTypeByTypeIndex(desc.typeIndex)
                    if (!functionTypesEquivalent(module, expected, tag.typeContext, tag.type)) {
                        incompatible(import, "tag type $expected", tag.type.toString())
                    }
                    tags += tag
                }
            }
        }

        return Instance.instantiate(
            store,
            module,
            ResolvedImports(functions, memories, tables, globals, tags),
        )
    }

    private fun checkMemoryImport(import: Import, expected: MemoryType, actual: MemoryInstance) {
        val limits = expected.limits
        val maximumMatches =
            limits.max == null || actual.maxPages?.let { it <= limits.max } == true
        if (
            actual.indexType != limits.indexType ||
            actual.sizeInPages < limits.min ||
            !maximumMatches
        ) {
            incompatible(import, "memory type $expected", "runtime memory")
        }
    }

    private fun checkTableImport(
        module: Module,
        import: Import,
        expected: TableType,
        actual: TableInstance,
    ) {
        val limits = expected.limits
        val maximumMatches =
            limits.max == null || actual.max?.toULong()?.let { it <= limits.max } == true
        if (
            !valueTypesEquivalent(
                module,
                expected.element,
                actual.typeContext,
                actual.elementType,
            ) ||
            actual.size.toULong() < limits.min ||
            !maximumMatches
        ) {
            incompatible(import, "table type $expected", "runtime table")
        }
    }

    private fun wrongKind(import: Import, expected: String): Nothing =
        throw LinkException("import is not a $expected", import.module, import.field)

    private fun incompatible(import: Import, expected: String, actual: String): Nothing =
        throw LinkException(
            "incompatible import: expected $expected, found $actual",
            import.module,
            import.field,
        )
}

/** A typed callable function export. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ExportedFunction internal constructor(
    public val type: FuncType,
    private val instance: Instance,
    private val functionIndex: Int,
) {
    public suspend fun invoke(
        arguments: List<Value> = emptyList(),
        machine: Machine = Interpreter(),
    ): List<Value> = machine.invoke(instance, functionIndex, arguments)
}
