package io.heapy.kwasm.tck

import io.heapy.kwasm.ExportDesc
import io.heapy.kwasm.FuncRefType
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.GlobalInstance
import io.heapy.kwasm.GlobalType
import io.heapy.kwasm.GuestFunctionAddress
import io.heapy.kwasm.HostFunction
import io.heapy.kwasm.HostImport
import io.heapy.kwasm.ImportDesc
import io.heapy.kwasm.IndexType
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Interpreter
import io.heapy.kwasm.Limits
import io.heapy.kwasm.LinkException
import io.heapy.kwasm.Machine
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.MemoryType
import io.heapy.kwasm.Module
import io.heapy.kwasm.Mutability
import io.heapy.kwasm.RefType
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.Store
import io.heapy.kwasm.TableInstance
import io.heapy.kwasm.TableType
import io.heapy.kwasm.TagInstance
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value

public fun interface TckFunction {
    public suspend fun invoke(arguments: List<Value>): List<Value>
}

/** An external value visible through a module-registration namespace. */
public sealed class TckExtern {
    public data class Function(
        public val type: FuncType,
        public val guestAddress: GuestFunctionAddress? = null,
        public val function: TckFunction,
    ) : TckExtern()

    public data class Memory(public val memory: MemoryInstance) : TckExtern()
    public data class Table(public val table: TableInstance) : TckExtern()
    public data class Global(public val global: GlobalInstance) : TckExtern()
    public data class Tag(public val tag: TagInstance) : TckExtern()
}

public data class TckRegisteredModule(public val exports: Map<String, TckExtern>)

/** Stateful name and registration environment described by the spec-script grammar. */
public class TckModuleRegistry {
    private val registered: MutableMap<String, TckRegisteredModule> = mutableMapOf()
    private val namedInstances: MutableMap<String, Instance> = mutableMapOf()

    public var currentInstance: Instance? = null
        private set

    public fun installInstance(name: String?, instance: Instance, machine: Machine = Interpreter()) {
        currentInstance = instance
        if (name != null) namedInstances[name] = instance
        // Named modules are action targets, not import namespaces until a register command.
        if (name != null) namedInstances[name.removePrefix("$")] = instance
    }

    public fun installExternal(alias: String, module: TckRegisteredModule) {
        registered[alias] = module
    }

    public fun register(alias: String, moduleName: String?, machine: Machine = Interpreter()) {
        val instance = resolveActionInstance(moduleName)
        registered[alias] = instance.asRegisteredModule(machine)
    }

    public fun resolveImportModule(alias: String): TckRegisteredModule? = registered[alias]

    public fun resolveActionInstance(name: String?): Instance {
        if (name == null) return currentInstance
            ?: throw LinkException("no module has been instantiated")
        return namedInstances[name] ?: namedInstances[name.removePrefix("$")]
            ?: throw LinkException("unknown module '$name'")
    }

    public companion object {
        /** Registry preloaded with the standard `spectest` imports used by the core suite. */
        public fun withSpectest(): TckModuleRegistry = TckModuleRegistry().apply {
            installExternal("spectest", spectestModule())
        }
    }
}

/** Resolves module imports by the registered module and export names in a script. */
public class TckLinker(private val machine: Machine = Interpreter()) {
    public fun instantiate(
        module: Module,
        registry: TckModuleRegistry,
        store: Store = Store(),
    ): Instance {
        val functions = mutableListOf<HostImport>()
        val memories = mutableListOf<MemoryInstance>()
        val tables = mutableListOf<TableInstance>()
        val globals = mutableListOf<GlobalInstance>()
        val tags = mutableListOf<TagInstance>()

        module.imports.forEach { import ->
            val namespace = registry.resolveImportModule(import.module)
                ?: throw LinkException(
                    "unknown import module '${import.module}'",
                    import.module,
                    import.field,
                )
            val external = namespace.exports[import.field]
                ?: throw LinkException(
                    "unknown import '${import.field}'",
                    import.module,
                    import.field,
                )
            when (import.desc) {
                is ImportDesc.Function -> {
                    val function = external as? TckExtern.Function
                        ?: wrongKind(import.module, import.field, "function")
                    functions += HostImport(
                        function.type,
                        function.guestAddress,
                        HostFunction(function.function::invoke),
                    )
                }
                is ImportDesc.Memory -> {
                    val memory = (external as? TckExtern.Memory)?.memory
                        ?: wrongKind(import.module, import.field, "memory")
                    memories += memory
                }
                is ImportDesc.Table -> {
                    val table = (external as? TckExtern.Table)?.table
                        ?: wrongKind(import.module, import.field, "table")
                    tables += table
                }
                is ImportDesc.Global -> {
                    val global = (external as? TckExtern.Global)?.global
                        ?: wrongKind(import.module, import.field, "global")
                    globals += global
                }
                is ImportDesc.Tag -> {
                    val tag = (external as? TckExtern.Tag)?.tag
                        ?: wrongKind(import.module, import.field, "tag")
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

    private fun wrongKind(module: String, name: String, expected: String): Nothing =
        throw LinkException("import is not a $expected", module, name)
}

private fun Instance.asRegisteredModule(machine: Machine): TckRegisteredModule {
    val external = exportsByName.mapValues { (_, export) ->
        when (val description = export.desc) {
            is ExportDesc.Function -> TckExtern.Function(
                type = functionType(description.index),
                guestAddress = GuestFunctionAddress(this, description.index),
            ) { arguments ->
                machine.invoke(this, description.index, arguments)
            }
            is ExportDesc.Memory -> TckExtern.Memory(memories[description.index])
            is ExportDesc.Table -> TckExtern.Table(tables[description.index])
            is ExportDesc.Global -> TckExtern.Global(globals[description.index])
            is ExportDesc.Tag -> TckExtern.Tag(tags[description.index])
        }
    }
    return TckRegisteredModule(external)
}

private fun spectestModule(): TckRegisteredModule {
    val i32 = GlobalInstance(GlobalType(ValType.I32, Mutability.CONST)).apply { init(Value.I32(666)) }
    val i64 = GlobalInstance(GlobalType(ValType.I64, Mutability.CONST)).apply { init(Value.I64(666L)) }
    val f32 = GlobalInstance(GlobalType(ValType.F32, Mutability.CONST)).apply { init(Value.F32(666.6f)) }
    val f64 = GlobalInstance(GlobalType(ValType.F64, Mutability.CONST)).apply { init(Value.F64(666.6)) }
    val memory = MemoryInstance(MemoryType(Limits(1u, 2u, indexType = IndexType.I32)))
    val table = TableInstance(TableType(FuncRefType, Limits(10u, 20u)))

    fun print(params: List<io.heapy.kwasm.ValType>): TckExtern.Function =
        TckExtern.Function(FuncType(params, emptyList())) { emptyList() }

    return TckRegisteredModule(
        mapOf(
            "print" to print(emptyList()),
            "print_i32" to print(listOf(ValType.I32)),
            "print_i64" to print(listOf(ValType.I64)),
            "print_f32" to print(listOf(ValType.F32)),
            "print_f64" to print(listOf(ValType.F64)),
            "print_i32_f32" to print(listOf(ValType.I32, ValType.F32)),
            "print_f64_f64" to print(listOf(ValType.F64, ValType.F64)),
            "global_i32" to TckExtern.Global(i32),
            "global_i64" to TckExtern.Global(i64),
            "global_f32" to TckExtern.Global(f32),
            "global_f64" to TckExtern.Global(f64),
            "memory" to TckExtern.Memory(memory),
            "table" to TckExtern.Table(table),
        ),
    )
}
