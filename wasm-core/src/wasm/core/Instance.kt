package io.heapy.kwasm

/**
 * A resolved module instance: holds runtime state (memories, tables, globals)
 * plus the resolved function index space (imports followed by locals).
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class Instance(
    public val store: Store,
    public val module: Module,
    public val imports: ResolvedImports,
) {
    public constructor(module: Module, imports: ResolvedImports = ResolvedImports()) :
        this(Store(), module, imports)

    private val importsValidated: Unit = validateResolvedImports()

    public val memories: List<MemoryInstance> =
        (imports.memories + module.memories.map { MemoryInstance(it.type) })
    public val tables: List<TableInstance> =
        (imports.tables + module.tables.map { TableInstance(it.type, module) })
    public val globals: MutableList<GlobalInstance> =
        (imports.globals + module.globals.map { GlobalInstance(it.type, module) }).toMutableList()
    public val tags: List<TagInstance> =
        imports.tags + module.tags.map {
            TagInstance(module.functionTypeByTypeIndex(it.typeIndex), module)
        }
    internal val elementSegments: MutableList<ElementSegment> = module.elementSegments.toMutableList()
    internal val dataSegments: MutableList<DataSegment> = module.dataSegments.toMutableList()

    /** Absolute function index space: host imports first, then locals. */
    public val functionCount: Int
        get() = imports.functions.size + module.functions.size

    public fun isImportedFunction(absIndex: Int): Boolean = absIndex < imports.functions.size

    internal fun importedFunction(absIndex: Int): HostImport = imports.functions[absIndex]

    /** Exports indexed by name. */
    public val exportsByName: Map<String, Export> = module.exports.associateBy { it.name }

    init {
        try {
            initializeGlobals()
            initializeTables()
            initializeDataSegments()
        } catch (failure: WasmInstantiationException) {
            throw failure
        } catch (trap: WasmTrap) {
            throw InstantiationFailure(trap.message ?: trap.kind.specMessage, trap)
        } catch (failure: RuntimeException) {
            throw InstantiationFailure(failure.message ?: "runtime initialization failed", failure)
        }
        store.register(this)
    }

    private fun validateResolvedImports() {
        validateImportCount("function", module.importedFunctionCount, imports.functions.size)
        validateImportCount("memory", module.importedMemoryCount, imports.memories.size)
        validateImportCount("table", module.importedTableCount, imports.tables.size)
        validateImportCount("global", module.importedGlobalCount, imports.globals.size)
        validateImportCount("tag", module.importedTagCount, imports.tags.size)

        var functionIndex = 0
        var memoryIndex = 0
        var tableIndex = 0
        var globalIndex = 0
        var tagIndex = 0
        module.imports.forEach { import ->
            when (val desc = import.desc) {
                is ImportDesc.Function -> {
                    val expected = module.functionTypeByTypeIndex(desc.typeIndex)
                    val resolved = imports.functions[functionIndex++]
                    val actual = resolved.type
                    val actualModule = resolved.resolvedTypeContext()
                    if (!functionTypesEquivalent(module, expected, actualModule, actual)) {
                        incompatibleImport(import, expected, actual)
                    }
                }
                is ImportDesc.Memory -> {
                    val actual = imports.memories[memoryIndex++]
                    val expected = desc.type.limits
                    val validMaximum =
                        expected.max == null || actual.maxPages?.let { it <= expected.max } == true
                    if (
                        actual.indexType != expected.indexType ||
                        actual.sizeInPages < expected.min ||
                        !validMaximum
                    ) {
                        incompatibleImport(import, desc.type, "runtime memory")
                    }
                }
                is ImportDesc.Table -> {
                    val actual = imports.tables[tableIndex++]
                    val expected = desc.type
                    val validMaximum =
                        expected.limits.max == null ||
                            actual.max?.toULong()?.let { it <= expected.limits.max } == true
                    if (
                        !valueTypesEquivalent(
                            module,
                            expected.element,
                            actual.typeContext,
                            actual.elementType,
                        ) ||
                        actual.size.toULong() < expected.limits.min ||
                        !validMaximum
                    ) {
                        incompatibleImport(import, expected, "runtime table")
                    }
                }
                is ImportDesc.Global -> {
                    val actual = imports.globals[globalIndex++]
                    val valueTypeMatches =
                        if (desc.type.mutability == Mutability.VAR) {
                            valueTypesEquivalent(
                                module,
                                desc.type.type,
                                actual.typeContext,
                                actual.type.type,
                            )
                        } else {
                            valueTypeSubtypeAcross(
                                actual.typeContext,
                                actual.type.type,
                                module,
                                desc.type.type,
                            )
                        }
                    if (
                        actual.type.mutability != desc.type.mutability ||
                        !valueTypeMatches
                    ) {
                        incompatibleImport(import, desc.type, actual.type)
                    }
                }
                is ImportDesc.Tag -> {
                    val expected = module.functionTypeByTypeIndex(desc.typeIndex)
                    val actual = imports.tags[tagIndex++]
                    if (!functionTypesEquivalent(module, expected, actual.typeContext, actual.type)) {
                        incompatibleImport(import, expected, actual.type)
                    }
                }
            }
        }
    }

    private fun validateImportCount(kind: String, expected: Int, actual: Int) {
        if (actual != expected) {
            throw LinkException("expected $expected $kind imports, received $actual")
        }
    }

    private fun incompatibleImport(import: Import, expected: Any, actual: Any): Nothing =
        throw LinkException(
            "incompatible import: expected $expected, found $actual",
            import.module,
            import.field,
        )

    private fun initializeGlobals() {
        val evaluator = ConstExprEvaluator(this)
        val importedCount = imports.globals.size
        module.globals.forEachIndexed { i, g ->
            val v = evaluator.eval(g.init, g.type.type)
            globals[importedCount + i].init(v)
        }
    }

    private fun initializeTables() {
        val evaluator = ConstExprEvaluator(this)
        module.tables.forEachIndexed { index, declaration ->
            val table = tables[imports.tables.size + index]
            val initial = evaluator.evalRef(declaration.init, declaration.type.element)
            repeat(table.size) { table.set(it, initial) }
        }
        module.elementSegments.forEachIndexed { segmentIndex, seg ->
            when (val mode = seg.mode) {
                is ElementMode.Active -> {
                    val table = tables[mode.tableIndex]
                    val offset = when (table.indexType) {
                        IndexType.I32 -> (evaluator.eval(mode.offset, ValType.I32) as Value.I32).v.toUInt().toULong()
                        IndexType.I64 -> (evaluator.eval(mode.offset, ValType.I64) as Value.I64).v.toULong()
                    }
                    val tableSize = table.size.toULong()
                    val elementCount = seg.exprs.size.toULong()
                    if (elementCount > tableSize || offset > tableSize - elementCount) {
                        val firstInvalid = if (offset >= tableSize) offset else tableSize
                        throw Trap.oobTable(
                            if (firstInvalid > Int.MAX_VALUE.toULong()) {
                                Int.MAX_VALUE
                            } else {
                                firstInvalid.toInt()
                            },
                            table.size,
                        )
                    }
                    // Each active segment is atomic. Earlier segments targeting
                    // an imported table remain visible after instantiation
                    // fails, but a failing segment must not leave a partial
                    // prefix behind.
                    val values = seg.exprs.map { expr ->
                        evaluator.evalRef(expr, seg.activeType ?: FuncRefType)
                    }
                    values.forEachIndexed { i, refValue ->
                        table.set((offset + i.toULong()).toInt(), refValue)
                    }
                    // Active segments are implicitly dropped once instance
                    // initialization has consumed them.
                    elementSegments[segmentIndex] = seg.copy(exprs = emptyList())
                }
                is ElementMode.Passive -> {
                    // Passive segments remain available to table.init.
                }
                is ElementMode.Declarative -> {
                    // Declarative segments exist only to declare ref.func
                    // references and are dropped during instantiation.
                    elementSegments[segmentIndex] = seg.copy(exprs = emptyList())
                }
            }
        }
    }

    private fun initializeDataSegments() {
        val evaluator = ConstExprEvaluator(this)
        module.dataSegments.forEachIndexed { segmentIndex, seg ->
            when (val mode = seg.mode) {
                is DataMode.Active -> {
                    val mem = memories[mode.memoryIndex]
                    val address = when (mem.indexType) {
                        IndexType.I32 ->
                            (evaluator.eval(mode.offset, ValType.I32) as Value.I32).v.toUInt().toULong()
                        IndexType.I64 ->
                            (evaluator.eval(mode.offset, ValType.I64) as Value.I64).v.toULong()
                    }
                    if (
                        seg.init.size > mem.byteSize ||
                        address > (mem.byteSize - seg.init.size).toULong()
                    ) {
                        throw Trap.oobMemory(
                            if (address > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else address.toLong(),
                            seg.init.size,
                        )
                    }
                    seg.init.copyInto(mem.data(), address.toInt(), 0, seg.init.size)
                    // Active data is no longer observable by memory.init after
                    // the instantiation expression has initialized memory.
                    dataSegments[segmentIndex] = seg.dropped()
                }
                DataMode.Passive -> Unit
            }
        }
    }

    /** Run the start function if present. */
    public suspend fun runStart(machine: Machine = Interpreter()) {
        val s = module.startFunction ?: return
        machine.invoke(this, s, emptyList())
    }

    public fun functionType(absIndex: Int): FuncType {
        val imported = imports.functions
        return if (absIndex < imported.size) {
            imported[absIndex].type
        } else {
            val local = module.functions[absIndex - imported.size]
            module.functionTypeByTypeIndex(local.typeIndex)
        }
    }

    /**
     * Module whose type-index space owns [functionType]'s indexed references.
     *
     * Imported guest functions can be re-exported through arbitrarily long
     * alias chains, so resolve this iteratively just like the interpreter's
     * call path. A host signature has no module-relative type context unless
     * the linker retained one while adapting a cross-store guest export.
     */
    internal fun functionTypeContext(absIndex: Int): Module? {
        var currentInstance = this
        var currentIndex = absIndex
        val visited = mutableSetOf<GuestFunctionAddress>()
        while (currentInstance.isImportedFunction(currentIndex)) {
            val imported = currentInstance.importedFunction(currentIndex)
            val guest = imported.guestAddress ?: return imported.typeContext
            if (!visited.add(guest)) return null
            currentInstance = guest.instance
            currentIndex = guest.functionIndex
        }
        return currentInstance.module
    }

    /** Call a host-imported function by absolute index. */
    public suspend fun callHost(absIndex: Int, args: List<Value>): List<Value> {
        val function = imports.functions[absIndex].fn
        return if (function is CallerAwareHostFunctionAdapter) {
            function.delegate.call(
                context = HostCallContext(this, absIndex),
                args = args,
            )
        } else {
            function.call(args)
        }
    }

    /** Resolve an export by name, or null if absent. */
    public fun export(name: String): Export? = exportsByName[name]

    public fun exportedFunction(name: String): ExportedFunction? {
        val function = (exportsByName[name]?.desc as? ExportDesc.Function) ?: return null
        return ExportedFunction(functionType(function.index), this, function.index)
    }

    public fun exportedMemory(name: String): MemoryInstance? =
        (exportsByName[name]?.desc as? ExportDesc.Memory)?.let { memories[it.index] }

    public fun exportedTable(name: String): TableInstance? =
        (exportsByName[name]?.desc as? ExportDesc.Table)?.let { tables[it.index] }

    public fun exportedGlobal(name: String): GlobalInstance? =
        (exportsByName[name]?.desc as? ExportDesc.Global)?.let { globals[it.index] }

    public fun exportedTag(name: String): TagInstance? =
        (exportsByName[name]?.desc as? ExportDesc.Tag)?.let { tags[it.index] }

    public suspend fun invoke(
        exportName: String,
        arguments: List<Value> = emptyList(),
        machine: Machine = Interpreter(),
    ): List<Value> {
        val export = exportsByName[exportName]
            ?: throw LinkException("unknown export '$exportName'")
        val function = export.desc as? ExportDesc.Function
            ?: throw LinkException("export '$exportName' is not a function")
        return machine.invoke(this, function.index, arguments)
    }

    /** Continue the heap frames installed by snapshot restoration. */
    public suspend fun resume(machine: ResumableMachine = Interpreter()): List<Value> =
        machine.resume(this)

    internal fun captureRuntimeSnapshot(): RuntimeInstanceSnapshot =
        RuntimeInstanceSnapshot(
            memories = memories.map { RuntimeMemorySnapshot(it.data()) },
            tables = tables.map { table ->
                RuntimeTableSnapshot(List(table.size, table::get))
            },
            globals = globals.map { it.value },
            elementSegmentsDropped = elementSegments.map { it.exprs.isEmpty() },
            dataSegments = dataSegments.map { it.init },
        )

    internal fun validateRuntimeSnapshot(snapshot: RuntimeInstanceSnapshot) {
        if (snapshot.memories.size != memories.size) {
            throw SnapshotStateException(
                "snapshot has ${snapshot.memories.size} memories; instance has ${memories.size}",
            )
        }
        if (snapshot.tables.size != tables.size) {
            throw SnapshotStateException(
                "snapshot has ${snapshot.tables.size} tables; instance has ${tables.size}",
            )
        }
        val snapshotGlobals = snapshot.globals()
        if (snapshotGlobals.size != globals.size) {
            throw SnapshotStateException(
                "snapshot has ${snapshotGlobals.size} globals; instance has ${globals.size}",
            )
        }
        if (snapshot.elementSegmentsDropped.size != elementSegments.size) {
            throw SnapshotStateException(
                "snapshot has ${snapshot.elementSegmentsDropped.size} element segments; " +
                    "instance has ${elementSegments.size}",
            )
        }
        val snapshotDataSegments = snapshot.dataSegments()
        if (snapshotDataSegments.size != dataSegments.size) {
            throw SnapshotStateException(
                "snapshot has ${snapshotDataSegments.size} data segments; instance has ${dataSegments.size}",
            )
        }

        snapshot.memories.forEachIndexed { index, memory ->
            memories[index].validateSnapshotBytes(memory.bytes())
        }
        snapshot.tables.forEachIndexed { index, table ->
            val values = table.values()
            tables[index].validateSnapshotRefs(values)
            values.forEachIndexed { elementIndex, value ->
                validateSnapshotValue(value, "table $index element $elementIndex")
            }
        }
        snapshotGlobals.forEachIndexed { index, value ->
            if (!value.matches(globals[index].type.type, module)) {
                throw SnapshotStateException(
                    "global $index has ${value.valueType()}, expected ${globals[index].type.type}",
                )
            }
            validateSnapshotValue(value, "global $index")
        }
        snapshotDataSegments.forEachIndexed { index, bytes ->
            val original = module.dataSegments[index].init
            if (bytes.isNotEmpty() && !bytes.contentEquals(original)) {
                throw SnapshotStateException(
                    "data segment $index is neither its exact module payload nor the dropped empty state",
                )
            }
        }
    }

    internal fun restoreRuntimeSnapshot(snapshot: RuntimeInstanceSnapshot) {
        snapshot.memories.forEachIndexed { index, memory ->
            memories[index].restoreSnapshotBytes(memory.bytes())
        }
        snapshot.tables.forEachIndexed { index, table ->
            tables[index].restoreSnapshotRefs(table.values())
        }
        snapshot.globals().forEachIndexed { index, value ->
            globals[index].init(copySnapshotValue(value))
        }
        snapshot.elementSegmentsDropped.forEachIndexed { index, dropped ->
            val original = module.elementSegments[index]
            elementSegments[index] = if (dropped) original.copy(exprs = emptyList()) else original
        }
        snapshot.dataSegments().forEachIndexed { index, bytes ->
            dataSegments[index] = DataSegment(module.dataSegments[index].mode, bytes)
        }
    }

    /**
     * Bind detached heap nodes produced by the portable snapshot codec and
     * validate every value reachable through GC objects and exception payloads.
     *
     * This walk is iterative so hostile cyclic/deep graphs cannot consume the
     * host call stack. It mutates only the decoded graph, never live instance
     * state; [Store] still performs its full validation before installation.
     */
    internal fun rebindAndValidateSnapshotGraph(snapshot: RuntimeStoreSnapshot) {
        val pending = ArrayDeque<Pair<Value, String>>()
        snapshot.instance.tables.forEachIndexed { tableIndex, table ->
            table.values().forEachIndexed { elementIndex, value ->
                pending.addLast(value to "table $tableIndex element $elementIndex")
            }
        }
        snapshot.instance.globals().forEachIndexed { index, value ->
            pending.addLast(value to "global $index")
        }
        snapshot.valueStack().forEachIndexed { index, value ->
            pending.addLast(value to "value stack slot $index")
        }
        snapshot.frames.forEachIndexed { frameIndex, frame ->
            frame.locals().forEachIndexed { localIndex, value ->
                pending.addLast(value to "frame $frameIndex local $localIndex")
            }
            frame.controls.forEachIndexed { controlIndex, control ->
                control.caughtException?.let { exception ->
                    pending.addLast(
                        Value.Ref.Exn(exception) to
                            "frame $frameIndex control $controlIndex caught exception",
                    )
                }
            }
        }
        snapshot.pendingImport?.arguments()?.forEachIndexed { index, value ->
            pending.addLast(value to "pending import argument $index")
        }

        val seenObjects = mutableSetOf<GcObject>()
        val seenExceptions = mutableSetOf<GuestException>()
        while (pending.isNotEmpty()) {
            val (value, location) = pending.removeFirst()
            validateSnapshotValue(value, location)
            when (value) {
                is Value.Ref.Gc -> {
                    val objectValue = value.value ?: continue
                    if (!seenObjects.add(objectValue)) continue
                    val originalOwner = objectValue.owner
                    if (originalOwner != null && originalOwner !== this) {
                        throw SnapshotStateException(
                            "$location belongs to a different instance",
                        )
                    }
                    objectValue.owner = this
                    when (objectValue) {
                        is StructObject -> {
                            val type = module.types.getOrNull(objectValue.typeIndex)
                                as? StructType
                                ?: throw SnapshotStateException(
                                    "$location refers to non-struct type ${objectValue.typeIndex}",
                                )
                            if (objectValue.fields.size != type.fields.size) {
                                throw SnapshotStateException(
                                    "$location has ${objectValue.fields.size} fields; " +
                                        "type ${objectValue.typeIndex} has ${type.fields.size}",
                                )
                            }
                            objectValue.fields.forEachIndexed { index, fieldValue ->
                                val expected = type.fields[index].storage.snapshotValueType()
                                if (!snapshotGraphValueMatches(fieldValue, expected)) {
                                    throw SnapshotStateException(
                                        "$location field $index has ${fieldValue.valueType()}, " +
                                            "expected $expected",
                                    )
                                }
                                pending.addLast(fieldValue to "$location field $index")
                            }
                        }
                        is ArrayObject -> {
                            val type = module.types.getOrNull(objectValue.typeIndex)
                                as? ArrayType
                                ?: throw SnapshotStateException(
                                    "$location refers to non-array type ${objectValue.typeIndex}",
                                )
                            val expected = type.field.storage.snapshotValueType()
                            objectValue.elements.forEachIndexed { index, element ->
                                if (!snapshotGraphValueMatches(element, expected)) {
                                    throw SnapshotStateException(
                                        "$location element $index has ${element.valueType()}, " +
                                            "expected $expected",
                                    )
                                }
                                pending.addLast(element to "$location element $index")
                            }
                        }
                    }
                }
                is Value.Ref.Exn -> {
                    val exception = value.value ?: continue
                    if (!seenExceptions.add(exception)) continue
                    val tagIndex = exception.tagIndex
                        ?: tags.indexOfFirst { it === exception.tag }
                    if (tagIndex !in tags.indices) {
                        throw SnapshotStateException(
                            "$location refers to unknown exception tag $tagIndex",
                        )
                    }
                    exception.tag = tags[tagIndex]
                    val expected = exception.tag.type.params
                    if (exception.arguments.size != expected.size) {
                        throw SnapshotStateException(
                            "$location has ${exception.arguments.size} exception arguments; " +
                                "tag $tagIndex expects ${expected.size}",
                        )
                    }
                    exception.arguments.forEachIndexed { index, argument ->
                        if (!snapshotGraphValueMatches(argument, expected[index])) {
                            throw SnapshotStateException(
                                "$location argument $index has ${argument.valueType()}, " +
                                    "expected ${expected[index]}",
                            )
                        }
                        pending.addLast(argument to "$location argument $index")
                    }
                }
                is Value.Ref.AnyExtern ->
                    pending.addLast(value.external to "$location converted externref")
                else -> Unit
            }
        }
    }

    internal fun validateSnapshotValue(value: Value, location: String) {
        when (value) {
            is Value.V128 -> {
                if (value.bytes.size != 16) {
                    throw SnapshotStateException(
                        "$location has a v128 payload of ${value.bytes.size} bytes; expected 16",
                    )
                }
            }
            is Value.Ref.Func -> {
                if (value.index < -1 || value.index >= functionCount) {
                    throw SnapshotStateException(
                        "$location refers to function ${value.index}; function count is $functionCount",
                    )
                }
            }
            else -> Unit
        }
    }

    private fun snapshotGraphValueMatches(value: Value, expected: ValType): Boolean {
        val referenceType = normalizeRefType(expected) as? RefType
        return if (referenceType == null) {
            value.matches(expected, module)
        } else {
            val reference = value as? Value.Ref ?: return false
            // Graph descriptors are decoded before payloads. Bind a referenced
            // node before checking its nominal/abstract heap type so forward
            // edges and cycles validate independently of traversal order.
            if (reference is Value.Ref.Gc) {
                val objectValue = reference.value
                if (objectValue != null) {
                    if (objectValue.owner != null && objectValue.owner !== this) return false
                    objectValue.owner = this
                }
            }
            module.referenceMatches(reference, referenceType)
        }
    }

    public companion object {
        public fun instantiate(
            store: Store,
            module: Module,
            imports: ResolvedImports = ResolvedImports(),
        ): Instance = Instance(store, module, imports)
    }
}

private fun StorageType.snapshotValueType(): ValType = when (this) {
    StorageType.I8, StorageType.I16 -> ValType.I32
    is StorageType.Value -> type
}

/** Resolved imports supplied by the host (or linking other instances). */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ResolvedImports(
    public val functions: List<HostImport> = emptyList(),
    public val memories: List<MemoryInstance> = emptyList(),
    public val tables: List<TableInstance> = emptyList(),
    public val globals: List<GlobalInstance> = emptyList(),
    public val tags: List<TagInstance> = emptyList(),
)

/**
 * Host-provided function callable from WebAssembly. The host may trap by
 * throwing [Trap].
 *
 * Host code inherits the runtime's snapshot execution gate. Replacing the
 * coroutine [kotlin.coroutines.ContinuationInterceptor] (for example with
 * `withContext(Dispatchers.IO)`) temporarily leaves that gate. A switched
 * block may perform I/O, but must not read or mutate guest/store state or
 * registered snapshot-participant state. Apply its result only after returning
 * to this host function's inherited continuation, which is gated again.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface HostFunction {
    public suspend fun call(args: List<Value>): List<Value>
}

/**
 * Context supplied to a [CallerAwareHostFunction].
 *
 * [caller] is the guest instance that executed the import. It can be used to
 * resolve caller-owned memories and other exports without attaching mutable
 * guest state to a reusable host registration. [functionIndex] is the
 * absolute function index in that caller.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class HostCallContext internal constructor(
    public val caller: Instance,
    public val functionIndex: Int,
)

/**
 * Host function that needs access to the guest instance executing the import.
 *
 * Prefer this only for ABIs whose pointer arguments address caller-owned
 * memory. Ordinary scalar host functions should continue to use
 * [HostFunction].
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface CallerAwareHostFunction {
    public suspend fun call(context: HostCallContext, args: List<Value>): List<Value>
}

private class CallerAwareHostFunctionAdapter(
    val delegate: CallerAwareHostFunction,
) : HostFunction {
    override suspend fun call(args: List<Value>): List<Value> =
        error(
            "caller-aware host function requires Instance.callHost; " +
                "it cannot be invoked through HostImport.fn directly",
        )
}

/** Result of a host function that may have a typed signature. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class HostImport(
    public val type: FuncType,
    public val guestAddress: GuestFunctionAddress? = null,
    public val fn: HostFunction,
) {
    internal var typeContext: Module? = null
        private set

    public constructor(type: FuncType, fn: HostFunction) : this(type, null, fn)

    public constructor(type: FuncType, fn: CallerAwareHostFunction) :
        this(type, null, CallerAwareHostFunctionAdapter(fn))

    internal constructor(
        type: FuncType,
        guestAddress: GuestFunctionAddress?,
        typeContext: Module?,
        fn: HostFunction,
    ) : this(type, guestAddress, fn) {
        this.typeContext = typeContext
    }

    internal fun resolvedTypeContext(): Module? =
        guestAddress?.instance?.functionTypeContext(guestAddress.functionIndex) ?: typeContext
}

/** Direct cross-instance guest function address retained by [Linker]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class GuestFunctionAddress(
    public val instance: Instance,
    public val functionIndex: Int,
)
