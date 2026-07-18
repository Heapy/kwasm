package io.heapy.kwasm

import io.heapy.kwasm.Instr.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Suspendable interpreter whose value stack, call frames, and control frames
 * are heap data owned by [Store]. Guest calls never recurse on the host stack.
 */
@ExperimentalKwasmApi
public class Interpreter : ResumableMachine {
    override suspend fun invoke(
        instance: Instance,
        functionIndex: Int,
        arguments: List<Value>,
    ): List<Value> {
        try {
            return withContext(instance.store.executionContext(currentCoroutineContext())) {
                invokeConfined(instance, functionIndex, arguments)
            }
        } catch (failure: HostImportFailure) {
            throw failure.original
        }
    }

    private suspend fun invokeConfined(
        instance: Instance,
        functionIndex: Int,
        arguments: List<Value>,
    ): List<Value> {
        val store = instance.store
        val type = instance.functionType(functionIndex)
        requireArguments(instance, type, arguments, functionIndex)
        store.beginInvocation()
        try {
            call(
                requestedInstance = instance,
                requestedFunctionIndex = functionIndex,
                arguments = arguments,
                tail = false,
                entryCheckpoint = true,
            )
            run(store)
            val results = store.valueStack.toList()
            if (results.size != type.results.size) {
                throw ExecutionTrap(
                    TrapKind.UNREACHABLE_PARENT,
                    "function returned ${results.size} values; expected ${type.results.size}",
                )
            }
            return results
        } catch (cancelled: CancellationException) {
            store.poison()
            throw cancelled
        } catch (trap: WasmTrap) {
            val enriched = enrichTrap(store, trap)
            store.config.listener?.onTrap(instance, enriched)
            throw enriched
        } finally {
            if (!store.poisoned) store.finishInvocation()
        }
    }

    override suspend fun resume(instance: Instance): List<Value> {
        try {
            return withContext(instance.store.executionContext(currentCoroutineContext())) {
                resumeConfined(instance)
            }
        } catch (failure: HostImportFailure) {
            throw failure.original
        }
    }

    private suspend fun resumeConfined(instance: Instance): List<Value> {
        val store = instance.store
        val rootResultTypes =
            store.frames.firstOrNull()?.type?.results
                ?: store.pendingImport?.let { instance.functionType(it.functionIndex).results }
                ?: emptyList()
        val pending = store.beginRestoredInvocation()
        try {
            if (pending != null) {
                val type = instance.functionType(pending.functionIndex)
                store.enterHostImport(pending.functionIndex, pending.arguments)
                val results =
                    try {
                        instance.callHost(pending.functionIndex, pending.arguments)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (trap: WasmTrap) {
                        throw trap
                    } catch (failure: Throwable) {
                        throw HostImportFailure(failure, Unit)
                    } finally {
                        store.leaveHostImport()
                    }
                requireResults(instance, type, results, pending.functionIndex)
                results.forEach { store.valueStack.addLast(canonicalize(store, it)) }
            }
            run(store)
            val results = store.valueStack.toList()
            if (results.size != rootResultTypes.size) {
                throw ExecutionTrap(
                    TrapKind.UNREACHABLE_PARENT,
                    "restored invocation returned ${results.size} values; " +
                        "expected ${rootResultTypes.size}",
                )
            }
            return results
        } catch (cancelled: CancellationException) {
            store.poison()
            throw cancelled
        } catch (trap: WasmTrap) {
            val enriched = enrichTrap(store, trap)
            store.config.listener?.onTrap(instance, enriched)
            throw enriched
        } finally {
            if (!store.poisoned) store.finishInvocation()
        }
    }

    private suspend fun run(store: Store) {
        if (store.config.fuelEnabled) {
            runMetered(store)
        } else if (store.config.checkpointMode == CheckpointMode.CompiledOutEquivalent) {
            runWithoutCheckpoints(store)
        } else {
            runUnmetered(store)
        }
    }

    /**
     * Benchmark-only control loop representing a build with checkpoint calls
     * compiled out. Keep this structurally parallel to [runUnmetered]: the
     * absence of [Store.beforeUnmeteredInstruction] is the behavior under
     * measurement, not a different execution implementation.
     */
    private suspend fun runWithoutCheckpoints(store: Store) {
        while (store.frames.isNotEmpty()) {
            val frame = store.frames.last()
            val control = frame.controls.lastOrNull()
                ?: throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "function has no control frame")

            if (control.pc >= control.body.size) {
                finishControl(store, frame, control)
                continue
            }

            val instruction = control.body[control.pc]
            control.pc++
            try {
                executeInstruction(store, frame, instruction)
            } catch (thrown: GuestThrown) {
                if (!handleGuestException(store, thrown.exception)) {
                    throw UncaughtWasmException(thrown.exception)
                }
            }
            canonicalizeTop(store)
            store.ensureValueStackLimit()
        }
    }

    private suspend fun runUnmetered(store: Store) {
        while (store.frames.isNotEmpty()) {
            val frame = store.frames.last()
            val control = frame.controls.lastOrNull()
                ?: throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "function has no control frame")

            if (control.pc >= control.body.size) {
                finishControl(store, frame, control)
                continue
            }

            val instruction = control.body[control.pc]
            store.beforeUnmeteredInstruction(
                forceCheckpoint = instruction.requiresCallCheckpoint(),
            )
            control.pc++
            try {
                executeInstruction(store, frame, instruction)
            } catch (thrown: GuestThrown) {
                if (!handleGuestException(store, thrown.exception)) {
                    throw UncaughtWasmException(thrown.exception)
                }
            }
            canonicalizeTop(store)
            store.ensureValueStackLimit()
        }
    }

    private suspend fun runMetered(store: Store) {
        while (store.frames.isNotEmpty()) {
            val frame = store.frames.last()
            val control = frame.controls.lastOrNull()
                ?: throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "function has no control frame")

            if (control.pc >= control.body.size) {
                finishControl(store, frame, control)
                continue
            }

            val instruction = control.body[control.pc]
            store.beforeMeteredInstruction(
                instruction,
                forceCheckpoint = instruction.requiresCallCheckpoint(),
            )
            control.pc++
            try {
                executeInstruction(store, frame, instruction)
            } catch (thrown: GuestThrown) {
                if (!handleGuestException(store, thrown.exception)) {
                    throw UncaughtWasmException(thrown.exception)
                }
            }
            canonicalizeTop(store)
            store.ensureValueStackLimit()
        }
    }

    private suspend fun executeInstruction(
        store: Store,
        frame: GuestCallFrame,
        instruction: Instr,
    ) {
        val instance = frame.instance
        val stack = store.valueStack
        when (instruction) {
            is Block -> pushControl(frame, stack, ControlKind.Block, instruction.blockType, instruction.body)
            is Loop -> pushControl(frame, stack, ControlKind.Loop, instruction.blockType, instruction.body)
            is If -> {
                val condition = stack.removeLastI32()
                pushControl(
                    frame,
                    stack,
                    ControlKind.If,
                    instruction.blockType,
                    if (condition != 0) instruction.thenBody else instruction.elseBody,
                )
            }
            is TryTable -> pushControl(
                frame,
                stack,
                ControlKind.TryTable,
                instruction.blockType,
                instruction.body,
                GuestExceptionHandler.Standard(instruction.catches),
            )
            is LegacyTry -> pushControl(
                frame,
                stack,
                ControlKind.LegacyTry,
                instruction.blockType,
                instruction.body,
                GuestExceptionHandler.Legacy(
                    instruction.catches,
                    instruction.catchAll,
                    instruction.delegateDepth,
                ),
            )
            Else, End, Nop -> Unit
            Unreachable -> throw Trap.unreachable()
            Return -> finishFunction(store, frame)
            is Br -> branch(store, frame, instruction.depth)
            is BrIf -> {
                if (stack.removeLastI32() != 0) branch(store, frame, instruction.depth)
            }
            is BrTable -> {
                val selector = stack.removeLastI32()
                val depth =
                    if (selector < 0 || selector >= instruction.rawTargets.size) {
                        instruction.defaultTarget
                    } else {
                        instruction.rawTargets[selector]
                    }
                branch(store, frame, depth)
            }
            is Call -> {
                val type = instance.functionType(instruction.funcIndex)
                val arguments = popArguments(stack, type.params.size)
                call(instance, instruction.funcIndex, arguments, tail = false)
            }
            is CallIndirect -> {
                val reference = resolveIndirect(instance, instruction.tableIndex, instruction.typeIndex, stack)
                val target = reference.owner ?: instance
                val expected = instance.module.functionTypeByTypeIndex(instruction.typeIndex)
                val arguments = popArguments(stack, expected.params.size)
                call(target, reference.index, arguments, tail = false)
            }
            is ReturnCall -> {
                val type = instance.functionType(instruction.funcIndex)
                val arguments = popArguments(stack, type.params.size)
                call(instance, instruction.funcIndex, arguments, tail = true)
            }
            is ReturnCallIndirect -> {
                val reference = resolveIndirect(instance, instruction.tableIndex, instruction.typeIndex, stack)
                val target = reference.owner ?: instance
                val expected = instance.module.functionTypeByTypeIndex(instruction.typeIndex)
                val arguments = popArguments(stack, expected.params.size)
                call(target, reference.index, arguments, tail = true)
            }
            is CallRef -> {
                val reference = stack.removeLast() as Value.Ref.Func
                if (reference.isNullRef()) throw Trap.nullFunctionReference()
                val target = reference.owner ?: instance
                checkFunctionType(instance, target, reference.index, instruction.typeIndex)
                val expected = instance.module.functionTypeByTypeIndex(instruction.typeIndex)
                val arguments = popArguments(stack, expected.params.size)
                call(target, reference.index, arguments, tail = false)
            }
            is ReturnCallRef -> {
                val reference = stack.removeLast() as Value.Ref.Func
                if (reference.isNullRef()) throw Trap.nullFunctionReference()
                val target = reference.owner ?: instance
                checkFunctionType(instance, target, reference.index, instruction.typeIndex)
                val expected = instance.module.functionTypeByTypeIndex(instruction.typeIndex)
                val arguments = popArguments(stack, expected.params.size)
                call(target, reference.index, arguments, tail = true)
            }
            is Drop -> repeat(instruction.n) { stack.removeLast() }
            Select -> select(stack)
            is SelectT -> select(stack)
            is I32Const -> stack.addLast(Value.I32(instruction.value))
            is I64Const -> stack.addLast(Value.I64(instruction.value))
            is F32Const -> stack.addLast(Value.F32(instruction.value))
            is F64Const -> stack.addLast(Value.F64(instruction.value))
            is RefNull -> stack.addLast(nullRefFor(instruction.heap, instance.module))
            RefIsNull -> {
                val value = stack.removeLast()
                stack.addLast(Value.I32(if (value.isNullRef()) 1 else 0))
            }
            is RefFunc -> stack.addLast(Value.Ref.Func(instruction.funcIndex, instance))
            RefEq -> {
                val right = stack.removeLast() as Value.Ref
                val left = stack.removeLast() as Value.Ref
                stack.addLast(Value.I32(if (sameReference(left, right)) 1 else 0))
            }
            RefAsNonNull -> {
                val value = stack.last()
                if (value.isNullRef()) throw Trap.nullReference()
            }
            is BrOnNull -> {
                if (stack.last().isNullRef()) {
                    stack.removeLast()
                    branch(store, frame, instruction.depth)
                }
            }
            is BrOnNonNull -> {
                if (stack.last().isNullRef()) {
                    stack.removeLast()
                } else {
                    branch(store, frame, instruction.depth)
                }
            }
            is Throw -> {
                val tag = instance.tags[instruction.tagIndex]
                val arguments = takeTop(stack, tag.type.params.size)
                throw GuestThrown(GuestException(tag, arguments, instruction.tagIndex))
            }
            ThrowRef -> {
                val reference = stack.removeLast() as? Value.Ref.Exn ?: throw Trap.castFailure()
                throw GuestThrown(reference.value ?: throw Trap.nullReference())
            }
            is Rethrow -> {
                val targetIndex = frame.controls.lastIndex - instruction.depth
                val exception =
                    if (instruction.depth >= 0 && targetIndex >= 0) {
                        frame.controls[targetIndex].caughtException
                    } else {
                        null
                    }
                    ?: throw ExecutionTrap(
                        TrapKind.UNREACHABLE_PARENT,
                        "invalid rethrow depth ${instruction.depth}",
                    )
                throw GuestThrown(exception)
            }
            is Gc -> execGc(store, frame, instruction)
            is FcIndex -> execFcIndex(instance, frame, instruction, stack)
            is Load -> execLoad(instance, instruction, stack)
            is Instr.Store -> execStore(instance, instruction, stack)
            MemorySize -> execMemorySize(instance, 0, stack)
            is MemorySizeAt -> execMemorySize(instance, instruction.memoryIndex, stack)
            MemoryGrow -> execMemoryGrow(instance, 0, stack)
            is MemoryGrowAt -> execMemoryGrow(instance, instruction.memoryIndex, stack)
            is MemoryCopy -> execMemoryCopy(instance, instruction, stack)
            is MemoryFill -> execMemoryFill(instance, instruction, stack)
            is MemoryInit -> execMemoryInit(instance, instruction, stack)
            DataDrop -> Unit
            is Simple -> execSimple(instruction.opcode, stack)
            is Plain -> throw ExecutionTrap(
                TrapKind.UNREACHABLE,
                "unsupported plain opcode 0x${instruction.opcode.toString(16)}",
            )
            is RawImmediate -> throw ExecutionTrap(
                TrapKind.UNREACHABLE,
                "unsupported deferred feature opcode 0x${instruction.opcode.toString(16)}",
            )
        }
    }

    private suspend fun call(
        requestedInstance: Instance,
        requestedFunctionIndex: Int,
        arguments: List<Value>,
        tail: Boolean,
        entryCheckpoint: Boolean = false,
    ) {
        var instance = requestedInstance
        var functionIndex = requestedFunctionIndex
        val visitedAliases = mutableSetOf<GuestFunctionAddress>()
        while (instance.isImportedFunction(functionIndex)) {
            val guest = instance.importedFunction(functionIndex).guestAddress ?: break
            if (guest.instance.store !== requestedInstance.store) {
                throw LinkException("cross-instance guest call requires both instances to share a Store")
            }
            if (!visitedAliases.add(guest)) {
                throw LinkException("cyclic guest function import alias")
            }
            instance = guest.instance
            functionIndex = guest.functionIndex
        }

        val store = instance.store
        val type = instance.functionType(functionIndex)
        requireArguments(instance, type, arguments, functionIndex)
        val inheritedBase =
            if (tail) {
                val replaced = store.frames.removeLast()
                truncate(store.valueStack, replaced.stackBase)
                replaced.stackBase
            } else {
                store.valueStack.size
            }

        if (instance.isImportedFunction(functionIndex)) {
            store.enterHostImport(functionIndex, arguments)
            if (entryCheckpoint && store.config.checkpointMode == CheckpointMode.Enabled) {
                store.checkpoint(handleFuel = false)
                // A completed checkpoint reports Running; the pending import
                // must remain externally visible until its host call returns.
                store.enterHostImport(functionIndex, arguments)
            }
            store.config.listener?.onCallStarted(instance, functionIndex, arguments)
            val results =
                try {
                    instance.callHost(functionIndex, arguments)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (trap: WasmTrap) {
                    throw trap
                } catch (failure: Throwable) {
                    throw HostImportFailure(failure, Unit)
                } finally {
                    store.leaveHostImport()
                }
            requireResults(instance, type, results, functionIndex)
            for (result in results) store.valueStack.addLast(canonicalize(store, result))
            store.config.listener?.onCallFinished(instance, functionIndex, results)
            return
        }

        if (store.frames.size >= store.config.limits.maxFrames) {
            throw ExecutionTrap(
                TrapKind.CALL_STACK_EXHAUSTED,
                "frame count ${store.frames.size + 1} exceeds ${store.config.limits.maxFrames}",
            )
        }
        val function = instance.module.functions[functionIndex - instance.imports.functions.size]
        val locals = ArrayList<Value>(arguments.size + function.locals.size)
        locals.addAll(arguments)
        function.locals.forEach { locals.add(zeroOf(it, instance.module)) }
        val root = GuestControlFrame(
            kind = ControlKind.Function,
            body = function.body,
            pc = 0,
            stackBase = inheritedBase,
            parameterCount = 0,
            resultCount = type.results.size,
            labelArity = type.results.size,
        )
        val frame = GuestCallFrame(
            instance = instance,
            functionIndex = functionIndex,
            functionName = instance.module.nameSection?.functionNames?.get(functionIndex),
            type = type,
            locals = locals,
            stackBase = inheritedBase,
            controls = ArrayDeque<GuestControlFrame>().apply { addLast(root) },
        )
        store.frames.addLast(frame)
        if (entryCheckpoint && store.config.checkpointMode == CheckpointMode.Enabled) {
            store.checkpoint(handleFuel = false)
        }
        store.config.listener?.onCallStarted(instance, functionIndex, arguments)
    }

    private fun pushControl(
        frame: GuestCallFrame,
        stack: ArrayDeque<Value>,
        kind: ControlKind,
        blockType: BlockType,
        body: List<Instr>,
        exceptionHandler: GuestExceptionHandler? = null,
    ) {
        val signature = blockSignature(frame.instance.module, blockType)
        if (stack.size < signature.params.size) {
            throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "value stack underflow entering $kind")
        }
        frame.controls.addLast(
            GuestControlFrame(
                kind = kind,
                body = body,
                pc = 0,
                stackBase = stack.size - signature.params.size,
                parameterCount = signature.params.size,
                resultCount = signature.results.size,
                labelArity = if (kind == ControlKind.Loop) signature.params.size else signature.results.size,
                exceptionHandler = exceptionHandler,
            ),
        )
    }

    private suspend fun branch(store: Store, frame: GuestCallFrame, depth: Int) {
        if (depth < 0 || depth >= frame.controls.size) {
            throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "invalid branch depth $depth")
        }
        val targetIndex = frame.controls.lastIndex - depth
        val target = frame.controls[targetIndex]
        val values = takeTop(store.valueStack, target.labelArity)
        while (frame.controls.lastIndex > targetIndex) frame.controls.removeLast()
        truncate(store.valueStack, target.stackBase)
        values.forEach(store.valueStack::addLast)

        when (target.kind) {
            ControlKind.Loop -> {
                target.pc = 0
                if (store.config.checkpointMode == CheckpointMode.Enabled) {
                    store.checkpoint(handleFuel = false)
                }
            }
            ControlKind.Function -> finishFunction(store, frame, values)
            ControlKind.Block,
            ControlKind.If,
            ControlKind.TryTable,
            ControlKind.LegacyTry,
            -> frame.controls.removeLast()
        }
    }

    private suspend fun handleGuestException(
        store: Store,
        exception: GuestException,
    ): Boolean {
        for (frameIndex in store.frames.lastIndex downTo 0) {
            val frame = store.frames[frameIndex]
            var maximumControlIndex = frame.controls.lastIndex
            var controlIndex = maximumControlIndex
            while (controlIndex >= 0) {
                if (controlIndex > maximumControlIndex) {
                    controlIndex--
                    continue
                }
                val control = frame.controls[controlIndex]
                when (val handler = control.exceptionHandler) {
                    is GuestExceptionHandler.Standard -> {
                        val clause = handler.catches.firstOrNull { catch ->
                            when (catch) {
                                is CatchClause.Tagged -> frame.instance.tags[catch.tagIndex] === exception.tag
                                is CatchClause.All -> true
                            }
                        }
                        if (clause != null) {
                            unwindToHandler(store, frameIndex, controlIndex)
                            truncate(store.valueStack, control.stackBase)
                            when (clause) {
                                is CatchClause.Tagged -> {
                                    exception.arguments.forEach(store.valueStack::addLast)
                                    if (clause.withReference) {
                                        store.valueStack.addLast(Value.Ref.Exn(exception))
                                    }
                                    // Catch label indices are relative to the
                                    // context outside try_table. The handler
                                    // control is still installed here, so skip
                                    // it when resolving the encoded depth.
                                    branch(store, frame, clause.depth + 1)
                                }
                                is CatchClause.All -> {
                                    if (clause.withReference) {
                                        store.valueStack.addLast(Value.Ref.Exn(exception))
                                    }
                                    branch(store, frame, clause.depth + 1)
                                }
                            }
                            return true
                        }
                    }
                    is GuestExceptionHandler.Legacy -> {
                        val matching = handler.catches.firstOrNull {
                            frame.instance.tags[it.tagIndex] === exception.tag
                        }
                        val body = matching?.body ?: handler.catchAll
                        if (body != null) {
                            unwindToHandler(store, frameIndex, controlIndex)
                            truncate(store.valueStack, control.stackBase)
                            if (matching != null) {
                                exception.arguments.forEach(store.valueStack::addLast)
                            }
                            control.body = body
                            control.pc = 0
                            control.exceptionHandler = null
                            control.caughtException = exception
                            return true
                        }
                        if (handler.delegateDepth != null) {
                            maximumControlIndex =
                                (controlIndex - handler.delegateDepth - 1).coerceAtMost(controlIndex - 1)
                        }
                    }
                    null -> Unit
                }
                controlIndex--
            }
        }
        return false
    }

    private fun unwindToHandler(
        store: Store,
        frameIndex: Int,
        controlIndex: Int,
    ) {
        while (store.frames.lastIndex > frameIndex) store.frames.removeLast()
        val frame = store.frames.last()
        while (frame.controls.lastIndex > controlIndex) frame.controls.removeLast()
    }

    private fun sameReference(left: Value.Ref, right: Value.Ref): Boolean = when {
        left.isNullRef() && right.isNullRef() -> true
        left is Value.Ref.I31 && right is Value.Ref.I31 -> left.value == right.value
        left is Value.Ref.Func && right is Value.Ref.Func ->
            left.index == right.index && left.owner === right.owner
        left is Value.Ref.Extern && right is Value.Ref.Extern -> left.handle == right.handle
        left is Value.Ref.Host && right is Value.Ref.Host -> left.value === right.value
        left is Value.Ref.Gc && right is Value.Ref.Gc -> left.value === right.value
        left is Value.Ref.Exn && right is Value.Ref.Exn -> left.value === right.value
        left is Value.Ref.AnyExtern && right is Value.Ref.AnyExtern ->
            sameReference(left.external, right.external)
        else -> false
    }

    private fun finishControl(store: Store, frame: GuestCallFrame, control: GuestControlFrame) {
        val values = takeTop(store.valueStack, control.resultCount)
        truncate(store.valueStack, control.stackBase)
        values.forEach(store.valueStack::addLast)
        frame.controls.removeLast()
        if (control.kind == ControlKind.Function) {
            finishFunction(store, frame, valuesAlreadyOnStack = values)
        }
    }

    private fun finishFunction(
        store: Store,
        frame: GuestCallFrame,
        valuesAlreadyOnStack: List<Value>? = null,
    ) {
        val results = valuesAlreadyOnStack ?: takeTop(store.valueStack, frame.type.results.size)
        truncate(store.valueStack, frame.stackBase)
        results.forEach(store.valueStack::addLast)
        if (store.frames.lastOrNull() === frame) store.frames.removeLast()
        store.config.listener?.onCallFinished(frame.instance, frame.functionIndex, results)
    }

    private fun resolveIndirect(
        instance: Instance,
        tableIndex: Int,
        typeIndex: Int,
        stack: ArrayDeque<Value>,
    ): Value.Ref.Func {
        val table = instance.tables[tableIndex]
        val rawIndex = popIndex(stack, table.indexType)
        if (rawIndex >= table.size.toULong()) {
            throw Trap.undefinedElement(rawIndex.saturatedInt(), table.size)
        }
        val elementIndex = rawIndex.toInt()
        val reference = table.get(elementIndex)
        if (reference.isNullRef()) throw Trap.indirectNull()
        val function = reference as Value.Ref.Func
        val target = function.owner ?: instance
        checkFunctionType(instance, target, function.index, typeIndex)
        return function
    }

    private fun checkFunctionType(
        callingInstance: Instance,
        targetInstance: Instance,
        functionIndex: Int,
        expectedTypeIndex: Int,
    ) {
        val expected = callingInstance.module.functionTypeByTypeIndex(expectedTypeIndex)
        val actual = targetInstance.functionType(functionIndex)
        if (!functionTypesEquivalent(
                callingInstance.module,
                expected,
                targetInstance.module,
                actual,
            )
        ) {
            throw Trap.indirectTypeMismatch(
                expectedTypeIndex,
                targetInstance.module.functionTypeIndex(functionIndex),
            )
        }
    }

    private fun blockSignature(module: Module, blockType: BlockType): FuncType = when (blockType) {
        BlockType.Empty -> FuncType(emptyList(), emptyList())
        is BlockType.Single -> FuncType(emptyList(), listOf(blockType.type))
        is BlockType.TypeIndex -> module.functionTypeByTypeIndex(blockType.index)
    }

    private fun popArguments(stack: ArrayDeque<Value>, count: Int): List<Value> =
        takeTop(stack, count)

    private fun takeTop(stack: ArrayDeque<Value>, count: Int): List<Value> {
        if (count < 0 || stack.size < count) {
            throw ExecutionTrap(
                TrapKind.UNREACHABLE_PARENT,
                "value stack underflow: need $count values, have ${stack.size}",
            )
        }
        val values = ArrayList<Value>(count)
        repeat(count) { values.add(0, stack.removeLast()) }
        return values
    }

    private fun truncate(stack: ArrayDeque<Value>, size: Int) {
        if (size < 0 || size > stack.size) {
            throw ExecutionTrap(
                TrapKind.UNREACHABLE_PARENT,
                "invalid stack height $size for stack of ${stack.size}",
            )
        }
        while (stack.size > size) stack.removeLast()
    }

    private fun select(stack: ArrayDeque<Value>) {
        val condition = stack.removeLastI32()
        val second = stack.removeLast()
        val first = stack.removeLast()
        stack.addLast(if (condition != 0) first else second)
    }

    private fun requireArguments(
        instance: Instance,
        type: FuncType,
        arguments: List<Value>,
        functionIndex: Int,
    ) {
        if (arguments.size != type.params.size) {
            throw LinkException(
                "function $functionIndex argument arity ${arguments.size} does not match ${type.params.size}",
            )
        }
        val typeContext = instance.functionTypeContext(functionIndex)
        arguments.forEachIndexed { index, value ->
            if (!value.matches(type.params[index], typeContext)) {
                throw LinkException(
                    "function $functionIndex argument $index has ${value.valueType()}, expected ${type.params[index]}",
                )
            }
        }
    }

    private fun requireResults(
        instance: Instance,
        type: FuncType,
        results: List<Value>,
        functionIndex: Int,
    ) {
        if (results.size != type.results.size) {
            throw LinkException(
                "host function $functionIndex returned ${results.size} values, expected ${type.results.size}",
            )
        }
        val typeContext = instance.functionTypeContext(functionIndex)
        results.forEachIndexed { index, value ->
            if (!value.matches(type.results[index], typeContext)) {
                throw LinkException(
                    "host function $functionIndex result $index has ${value.valueType()}, expected ${type.results[index]}",
                )
            }
        }
    }

    private fun enrichTrap(store: Store, trap: WasmTrap): WasmTrap {
        if (trap.guestStack.isNotEmpty()) return trap
        val frame = store.frames.lastOrNull()
        return ExecutionTrap(
            kind = trap.kind,
            detail = trap.message,
            functionIndex = frame?.functionIndex,
            functionName = frame?.functionName,
            guestStack = store.guestStack(),
        )
    }

    private fun canonicalizeTop(store: Store) {
        if (!store.config.canonicalizeNaNs || store.valueStack.isEmpty()) return
        val index = store.valueStack.lastIndex
        store.valueStack[index] = canonicalize(store, store.valueStack[index])
    }

    private fun canonicalize(store: Store, value: Value): Value {
        if (!store.config.canonicalizeNaNs) return value
        return when (value) {
            is Value.F32 -> if (value.v.isNaN()) Value.F32(Float.fromBits(0x7FC00000)) else value
            is Value.F64 -> if (value.v.isNaN()) Value.F64(Double.fromBits(0x7FF8000000000000L)) else value
            else -> value
        }
    }

    private fun nullRefFor(heap: HeapType, module: Module): Value.Ref = when (heap) {
        HeapType.Extern, HeapType.NoExtern -> Value.NULL_EXTERN
        HeapType.Exn, HeapType.NoExn -> Value.NULL_EXN
        HeapType.Func, HeapType.NoFunc -> Value.NULL_FUNC
        is HeapType.Index ->
            if (module.types.getOrNull(heap.value) is FuncType) Value.NULL_FUNC else Value.NULL_GC
        else -> Value.NULL_GC
    }

    private suspend fun execGc(store: Store, frame: GuestCallFrame, instruction: Gc) {
        val instance = frame.instance
        val module = instance.module
        val stack = store.valueStack

        when (instruction.subOpcode) {
            0 -> {
                val type = structType(module, instruction.firstIndex)
                val fields = MutableList<Value>(type.fields.size) { Value.I32(0) }
                for (index in type.fields.indices.reversed()) {
                    fields[index] = coerceStoredValue(type.fields[index].storage, stack.removeLast())
                }
                stack.addLast(
                    Value.Ref.Gc(StructObject(instance, instruction.firstIndex, fields)),
                )
            }
            1 -> {
                val type = structType(module, instruction.firstIndex)
                val fields = type.fields.map {
                    defaultStorageValue(it.storage, module)
                }.toMutableList()
                stack.addLast(
                    Value.Ref.Gc(StructObject(instance, instruction.firstIndex, fields)),
                )
            }
            in 2..5 -> {
                val type = structType(module, instruction.firstIndex)
                val field = type.fields.getOrNull(instruction.secondIndex)
                    ?: throw ExecutionTrap(
                        TrapKind.UNREACHABLE_PARENT,
                        "unknown struct field ${instruction.secondIndex}",
                    )
                if (instruction.subOpcode == 5) {
                    val value = coerceStoredValue(field.storage, stack.removeLast())
                    val struct = popStruct(stack)
                    struct.fields[instruction.secondIndex] = value
                } else {
                    val struct = popStruct(stack)
                    stack.addLast(
                        loadFieldValue(
                            field.storage,
                            struct.fields[instruction.secondIndex],
                            signed = instruction.subOpcode == 3,
                            unsigned = instruction.subOpcode == 4,
                        ),
                    )
                }
            }
            6 -> {
                val type = arrayType(module, instruction.firstIndex)
                val length = popAllocationLength(stack)
                val initial = coerceStoredValue(type.field.storage, stack.removeLast())
                stack.addLast(
                    Value.Ref.Gc(
                        ArrayObject(
                            instance,
                            instruction.firstIndex,
                            MutableList(length) { initial },
                        ),
                    ),
                )
            }
            7 -> {
                val type = arrayType(module, instruction.firstIndex)
                val length = popAllocationLength(stack)
                val initial = defaultStorageValue(type.field.storage, module)
                stack.addLast(
                    Value.Ref.Gc(
                        ArrayObject(
                            instance,
                            instruction.firstIndex,
                            MutableList(length) { initial },
                        ),
                    ),
                )
            }
            8 -> {
                val type = arrayType(module, instruction.firstIndex)
                val count = instruction.count
                val elements = MutableList<Value>(count) { Value.I32(0) }
                for (index in elements.indices.reversed()) {
                    elements[index] = coerceStoredValue(type.field.storage, stack.removeLast())
                }
                stack.addLast(
                    Value.Ref.Gc(ArrayObject(instance, instruction.firstIndex, elements)),
                )
            }
            9 -> {
                val type = arrayType(module, instruction.firstIndex)
                val count = popUnsignedI32(stack)
                val offset = popUnsignedI32(stack)
                val data = instance.dataSegments.getOrNull(instruction.secondIndex)?.init
                    ?: throw Trap.arrayOutOfBounds(instruction.secondIndex, instance.dataSegments.size)
                val values = decodeArrayData(type.field.storage, data, offset, count)
                stack.addLast(
                    Value.Ref.Gc(ArrayObject(instance, instruction.firstIndex, values.toMutableList())),
                )
            }
            10 -> {
                val type = arrayType(module, instruction.firstIndex)
                val count = popUnsignedI32(stack)
                val offset = popUnsignedI32(stack)
                val segment = instance.elementSegments.getOrNull(instruction.secondIndex)
                    ?: throw Trap.arrayOutOfBounds(instruction.secondIndex, instance.elementSegments.size)
                if (offset > segment.exprs.size || count > segment.exprs.size - offset) {
                    throw Trap.arrayOutOfBounds(offset, segment.exprs.size)
                }
                val evaluator = ConstExprEvaluator(instance)
                val elementType = when (val mode = segment.mode) {
                    is ElementMode.Active -> segment.activeType ?: FuncRefType
                    is ElementMode.Passive -> mode.type
                    is ElementMode.Declarative -> mode.type
                }
                val values = segment.exprs.subList(offset, offset + count).map {
                    coerceStoredValue(type.field.storage, evaluator.evalRef(it, elementType))
                }
                stack.addLast(
                    Value.Ref.Gc(ArrayObject(instance, instruction.firstIndex, values.toMutableList())),
                )
            }
            in 11..14 -> {
                val type = arrayType(module, instruction.firstIndex)
                if (instruction.subOpcode == 14) {
                    val value = coerceStoredValue(type.field.storage, stack.removeLast())
                    val index = popUnsignedI32(stack)
                    val array = popArray(stack)
                    checkArrayIndex(array, index)
                    array.elements[index] = value
                } else {
                    val index = popUnsignedI32(stack)
                    val array = popArray(stack)
                    checkArrayIndex(array, index)
                    stack.addLast(
                        loadFieldValue(
                            type.field.storage,
                            array.elements[index],
                            signed = instruction.subOpcode == 12,
                            unsigned = instruction.subOpcode == 13,
                        ),
                    )
                }
            }
            15 -> stack.addLast(Value.I32(popArray(stack).elements.size))
            16 -> {
                val type = arrayType(module, instruction.firstIndex)
                val count = popUnsignedI32(stack)
                val value = coerceStoredValue(type.field.storage, stack.removeLast())
                val destination = popUnsignedI32(stack)
                val array = popArray(stack)
                checkArrayRange(array, destination, count)
                repeat(count) { array.elements[destination + it] = value }
            }
            17 -> {
                val count = popUnsignedI32(stack)
                val sourceOffset = popUnsignedI32(stack)
                val source = popArray(stack)
                val destinationOffset = popUnsignedI32(stack)
                val destination = popArray(stack)
                checkArrayRange(source, sourceOffset, count)
                checkArrayRange(destination, destinationOffset, count)
                val values = source.elements.subList(sourceOffset, sourceOffset + count).toList()
                values.forEachIndexed { index, value ->
                    destination.elements[destinationOffset + index] = value
                }
            }
            18 -> {
                val type = arrayType(module, instruction.firstIndex)
                val count = popUnsignedI32(stack)
                val sourceOffset = popUnsignedI32(stack)
                val destinationOffset = popUnsignedI32(stack)
                val destination = popArray(stack)
                checkArrayRange(destination, destinationOffset, count)
                val data = instance.dataSegments.getOrNull(instruction.secondIndex)?.init
                    ?: throw Trap.arrayOutOfBounds(instruction.secondIndex, instance.dataSegments.size)
                val values = decodeArrayData(type.field.storage, data, sourceOffset, count)
                values.forEachIndexed { index, value ->
                    destination.elements[destinationOffset + index] = value
                }
            }
            19 -> {
                val type = arrayType(module, instruction.firstIndex)
                val count = popUnsignedI32(stack)
                val sourceOffset = popUnsignedI32(stack)
                val destinationOffset = popUnsignedI32(stack)
                val destination = popArray(stack)
                checkArrayRange(destination, destinationOffset, count)
                val segment = instance.elementSegments.getOrNull(instruction.secondIndex)
                    ?: throw Trap.arrayOutOfBounds(instruction.secondIndex, instance.elementSegments.size)
                if (sourceOffset > segment.exprs.size || count > segment.exprs.size - sourceOffset) {
                    throw Trap.arrayOutOfBounds(sourceOffset, segment.exprs.size)
                }
                val evaluator = ConstExprEvaluator(instance)
                val elementType = when (val mode = segment.mode) {
                    is ElementMode.Active -> segment.activeType ?: FuncRefType
                    is ElementMode.Passive -> mode.type
                    is ElementMode.Declarative -> mode.type
                }
                repeat(count) { index ->
                    destination.elements[destinationOffset + index] = coerceStoredValue(
                        type.field.storage,
                        evaluator.evalRef(segment.exprs[sourceOffset + index], elementType),
                    )
                }
            }
            20, 21 -> {
                val value = stack.removeLast() as Value.Ref
                val target = instruction.targetType!!
                stack.addLast(Value.I32(if (module.referenceMatches(value, target)) 1 else 0))
            }
            22, 23 -> {
                val value = stack.removeLast() as Value.Ref
                val target = instruction.targetType!!
                if (!module.referenceMatches(value, target)) throw Trap.castFailure()
                stack.addLast(value)
            }
            24, 25 -> {
                val value = stack.last() as Value.Ref
                val matches = module.referenceMatches(value, instruction.targetType!!)
                if (
                    instruction.subOpcode == 24 && matches ||
                    instruction.subOpcode == 25 && !matches
                ) {
                    branch(store, frame, instruction.depth)
                }
            }
            26 -> {
                val value = stack.removeLast() as Value.Ref
                stack.addLast(
                    if (value.isNullRef()) Value.NULL_GC else Value.Ref.AnyExtern(value),
                )
            }
            27 -> {
                val value = stack.removeLast() as Value.Ref
                stack.addLast(
                    when {
                        value.isNullRef() -> Value.NULL_EXTERN
                        value is Value.Ref.AnyExtern -> value.external
                        else -> throw Trap.castFailure()
                    },
                )
            }
            28 -> {
                val value = stack.removeLastI32()
                stack.addLast(Value.Ref.I31(value shl 1 shr 1))
            }
            29 -> stack.addLast(Value.I32((stack.removeLast() as Value.Ref.I31).value))
            30 -> stack.addLast(Value.I32((stack.removeLast() as Value.Ref.I31).value and 0x7FFF_FFFF))
            else -> throw ExecutionTrap(
                TrapKind.UNREACHABLE_PARENT,
                "unknown GC opcode ${instruction.subOpcode}",
            )
        }
    }

    private fun structType(module: Module, index: Int): StructType =
        module.types.getOrNull(index) as? StructType
            ?: throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "type $index is not a struct")

    private fun arrayType(module: Module, index: Int): ArrayType =
        module.types.getOrNull(index) as? ArrayType
            ?: throw ExecutionTrap(TrapKind.UNREACHABLE_PARENT, "type $index is not an array")

    private fun popStruct(stack: ArrayDeque<Value>): StructObject {
        val reference = stack.removeLast() as? Value.Ref.Gc ?: throw Trap.castFailure()
        return reference.value as? StructObject ?: if (reference.value == null) {
            throw Trap.nullReference()
        } else {
            throw Trap.castFailure()
        }
    }

    private fun popArray(stack: ArrayDeque<Value>): ArrayObject {
        val reference = stack.removeLast() as? Value.Ref.Gc ?: throw Trap.castFailure()
        return reference.value as? ArrayObject ?: if (reference.value == null) {
            throw Trap.nullReference()
        } else {
            throw Trap.castFailure()
        }
    }

    private fun popAllocationLength(stack: ArrayDeque<Value>): Int = popUnsignedI32(stack)

    private fun popUnsignedI32(stack: ArrayDeque<Value>): Int {
        val value = stack.removeLastI32().toUInt()
        if (value > Int.MAX_VALUE.toUInt()) throw Trap.arrayOutOfBounds(Int.MAX_VALUE, 0)
        return value.toInt()
    }

    private fun checkArrayIndex(array: ArrayObject, index: Int) {
        if (index !in array.elements.indices) throw Trap.arrayOutOfBounds(index, array.elements.size)
    }

    private fun checkArrayRange(array: ArrayObject, offset: Int, count: Int) {
        if (offset < 0 || count < 0 || offset > array.elements.size - count) {
            throw Trap.arrayOutOfBounds(offset, array.elements.size)
        }
    }

    private fun defaultStorageValue(storage: StorageType, module: Module): Value = when (storage) {
        StorageType.I8, StorageType.I16 -> Value.I32(0)
        is StorageType.Value -> zeroOf(storage.type, module)
    }

    private fun coerceStoredValue(storage: StorageType, value: Value): Value = when (storage) {
        StorageType.I8 -> Value.I32(value.asI32() and 0xFF)
        StorageType.I16 -> Value.I32(value.asI32() and 0xFFFF)
        is StorageType.Value -> value
    }

    private fun loadFieldValue(
        storage: StorageType,
        value: Value,
        signed: Boolean,
        unsigned: Boolean,
    ): Value = when (storage) {
        StorageType.I8 -> {
            val bits = value.asI32() and 0xFF
            Value.I32(if (signed) bits shl 24 shr 24 else bits)
        }
        StorageType.I16 -> {
            val bits = value.asI32() and 0xFFFF
            Value.I32(if (signed) bits shl 16 shr 16 else bits)
        }
        is StorageType.Value -> {
            if (signed || unsigned) throw Trap.castFailure()
            value
        }
    }

    private fun decodeArrayData(
        storage: StorageType,
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ): List<Value> {
        val width = when (storage) {
            StorageType.I8 -> 1
            StorageType.I16 -> 2
            is StorageType.Value -> when (storage.type) {
                ValType.I32, ValType.F32 -> 4
                ValType.I64, ValType.F64 -> 8
                else -> throw Trap.castFailure()
            }
        }
        val byteCount = count.toLong() * width.toLong()
        if (
            byteCount > Int.MAX_VALUE ||
            offset < 0 ||
            offset > bytes.size - byteCount.toInt()
        ) {
            throw Trap.arrayOutOfBounds(offset, bytes.size)
        }
        return List(count) { index ->
            val position = offset + index * width
            when (storage) {
                StorageType.I8 -> Value.I32(bytes[position].toInt() and 0xFF)
                StorageType.I16 -> Value.I32(
                    (bytes[position].toInt() and 0xFF) or
                        ((bytes[position + 1].toInt() and 0xFF) shl 8),
                )
                is StorageType.Value -> when (storage.type) {
                    ValType.I32 -> Value.I32(readLittleI32(bytes, position))
                    ValType.I64 -> Value.I64(readLittleI64(bytes, position))
                    ValType.F32 -> Value.F32(Float.fromBits(readLittleI32(bytes, position)))
                    ValType.F64 -> Value.F64(Double.fromBits(readLittleI64(bytes, position)))
                    else -> throw Trap.castFailure()
                }
            }
        }
    }

    private fun readLittleI32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLittleI64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        repeat(8) { index ->
            result = result or ((bytes[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        return result
    }

    // ---- variable / reference / table / memory index ops (0x20..0x26, 0x23,0x24) ----

    private fun execFcIndex(
        instance: Instance,
        frame: GuestCallFrame,
        ins: FcIndex,
        stack: ArrayDeque<Value>,
    ) {
        when (ins.opcode) {
            0x20 -> stack.addLast(frame.locals[ins.index])                      // local.get
            0x21 -> frame.locals[ins.index] = stack.removeLast()                // local.set
            0x22 -> { val v = stack.last(); frame.locals[ins.index] = v }       // local.tee
            0x23 -> stack.addLast(instance.globals[ins.index].value)            // global.get
            0x24 -> instance.globals[ins.index].set(stack.removeLast())         // global.set
            0x25 -> {                                                           // table.get
                val table = instance.tables[ins.index]
                val index = popIndex(stack, table.indexType)
                if (index >= table.size.toULong()) {
                    throw Trap.oobTable(index.saturatedInt(), table.size)
                }
                stack.addLast(table.get(index.toInt()))
            }
            0x26 -> {                                                           // table.set
                val v = stack.removeLast() as Value.Ref
                val table = instance.tables[ins.index]
                val index = popIndex(stack, table.indexType)
                if (index >= table.size.toULong()) {
                    throw Trap.oobTable(index.saturatedInt(), table.size)
                }
                table.set(index.toInt(), v)
            }
            0x00_FC -> stack.addLast(Value.I32(truncSatF32ToI32S(stack.removeLastF32())))
            0x01_FC -> stack.addLast(Value.I32(truncSatF32ToI32U(stack.removeLastF32())))
            0x02_FC -> stack.addLast(Value.I32(truncSatF64ToI32S(stack.removeLastF64())))
            0x03_FC -> stack.addLast(Value.I32(truncSatF64ToI32U(stack.removeLastF64())))
            0x04_FC -> stack.addLast(Value.I64(truncSatF32ToI64S(stack.removeLastF32())))
            0x05_FC -> stack.addLast(Value.I64(truncSatF32ToI64U(stack.removeLastF32())))
            0x06_FC -> stack.addLast(Value.I64(truncSatF64ToI64S(stack.removeLastF64())))
            0x07_FC -> stack.addLast(Value.I64(truncSatF64ToI64U(stack.removeLastF64())))
            0x09_FC -> dataDrop(instance, ins.index)
            0x0C_FC -> {
                val count = stack.removeLastI32().toUInt().toULong()
                val source = stack.removeLastI32().toUInt().toULong()
                val table = instance.tables[ins.second]
                val destination = popIndex(stack, table.indexType)
                tableInit(instance, ins.second, ins.index, destination, source, count)
            }
            0x0D_FC -> elementDrop(instance, ins.index)
            0x0E_FC -> {
                val destinationTable = instance.tables[ins.index]
                val sourceTable = instance.tables[ins.second]
                val lengthType =
                    minimumIndexType(destinationTable.indexType, sourceTable.indexType)
                val count = popIndex(stack, lengthType)
                val source = popIndex(stack, sourceTable.indexType)
                val destination = popIndex(stack, destinationTable.indexType)
                tableCopy(instance, ins.index, ins.second, destination, source, count)
            }
            0x0F_FC -> {
                val table = instance.tables[ins.index]
                val n = popIndex(stack, table.indexType)
                val v = stack.removeLast() as Value.Ref
                val result =
                    if (n > Int.MAX_VALUE.toULong()) -1
                    else table.grow(n.toInt(), v)
                pushIndexResult(stack, table.indexType, result)
            }
            0x10_FC -> {
                val table = instance.tables[ins.index]
                pushIndex(stack, table.indexType, table.size.toULong())
            }
            0x11_FC -> {
                val table = instance.tables[ins.index]
                val count = popIndex(stack, table.indexType)
                val value = stack.removeLast() as Value.Ref
                val destination = popIndex(stack, table.indexType)
                tableFill(instance, ins.index, destination, value, count)
            }
            0xFB -> Unit // GC op skipped
            else -> throw Trap(TrapKind.UNREACHABLE, "unknown FcIndex opcode 0x${ins.opcode.toString(16)}")
        }
    }

    private fun tableInit(
        instance: Instance,
        tableIdx: Int,
        segIdx: Int,
        destination: ULong,
        source: ULong,
        count: ULong,
    ) {
        val table = instance.tables[tableIdx]
        val seg = instance.elementSegments.getOrNull(segIdx)
            ?: throw Trap(TrapKind.UNDEFINED_ELEMENT, "unknown element segment $segIdx")
        val evaluator = ConstExprEvaluator(instance)
        if (
            source > seg.exprs.size.toULong() ||
            count > seg.exprs.size.toULong() - source ||
            destination > table.size.toULong() ||
            count > table.size.toULong() - destination
        ) {
            throw Trap.oobTable(destination.saturatedInt(), table.size)
        }
        val d = destination.toInt()
        val s = source.toInt()
        val n = count.toInt()
        for (k in 0 until n) {
            val refValue = evaluator.evalRef(seg.exprs[s + k], seg.activeType ?: FuncRefType)
            table.set(d + k, refValue)
        }
    }

    private fun elementDrop(instance: Instance, segIdx: Int) {
        // Mark segment as dropped by clearing its expressions.
        val seg = instance.elementSegments[segIdx]
        // We cannot reassign; clear contents via copy.
        instance.elementSegments[segIdx] = seg.copy(exprs = emptyList())
    }

    private fun tableCopy(
        instance: Instance,
        dstIdx: Int,
        srcIdx: Int,
        destination: ULong,
        source: ULong,
        count: ULong,
    ) {
        val dst = instance.tables[dstIdx]
        val src = instance.tables[srcIdx]
        if (
            source > src.size.toULong() ||
            count > src.size.toULong() - source ||
            destination > dst.size.toULong() ||
            count > dst.size.toULong() - destination
        ) {
            throw Trap.oobTable(destination.saturatedInt(), dst.size)
        }
        val d = destination.toInt()
        val s = source.toInt()
        val n = count.toInt()
        if (n == 0) return
        // Copy with overlap awareness.
        val tmp = ArrayList<Value.Ref>(n)
        for (k in 0 until n) tmp.add(src.get(s + k))
        for (k in 0 until n) dst.set(d + k, tmp[k])
    }

    private fun tableFill(
        instance: Instance,
        tableIdx: Int,
        destination: ULong,
        value: Value.Ref,
        count: ULong,
    ) {
        val table = instance.tables[tableIdx]
        if (
            destination > table.size.toULong() ||
            count > table.size.toULong() - destination
        ) {
            throw Trap.oobTable(destination.saturatedInt(), table.size)
        }
        val d = destination.toInt()
        repeat(count.toInt()) { table.set(d + it, value) }
    }

    private fun dataDrop(instance: Instance, segIdx: Int) {
        val seg = instance.dataSegments[segIdx]
        instance.dataSegments[segIdx] = seg.dropped()
    }

    private fun execMemoryCopy(instance: Instance, ins: MemoryCopy, stack: ArrayDeque<Value>) {
        val dst = instance.memories[ins.dstIndex]
        val src = instance.memories[ins.srcIndex]
        val lengthType = minimumIndexType(dst.indexType, src.indexType)
        val count = popIndex(stack, lengthType)
        val source = popIndex(stack, src.indexType)
        val destination = popIndex(stack, dst.indexType)
        if (
            source > src.byteSize.toULong() ||
            count > src.byteSize.toULong() - source
        ) {
            throw Trap.oobMemory(source.saturatedLong(), count.saturatedInt())
        }
        if (
            destination > dst.byteSize.toULong() ||
            count > dst.byteSize.toULong() - destination
        ) {
            throw Trap.oobMemory(destination.saturatedLong(), count.saturatedInt())
        }
        val n = count.toInt()
        val s = source.toInt()
        val d = destination.toInt()
        val buf = src.data().copyOfRange(s, s + n)
        buf.copyInto(dst.data(), d)
    }

    private fun execMemoryFill(instance: Instance, ins: MemoryFill, stack: ArrayDeque<Value>) {
        val mem = instance.memories[ins.dstIndex]
        val count = popIndex(stack, mem.indexType)
        val v = (stack.removeLast() as Value.I32).v.toByte()
        val destination = popIndex(stack, mem.indexType)
        if (
            destination > mem.byteSize.toULong() ||
            count > mem.byteSize.toULong() - destination
        ) {
            throw Trap.oobMemory(destination.saturatedLong(), count.saturatedInt())
        }
        val d = destination.toInt()
        val n = count.toInt()
        val data = mem.data()
        for (k in d until d + n) data[k] = v
    }

    private fun execMemoryInit(instance: Instance, ins: MemoryInit, stack: ArrayDeque<Value>) {
        val count = stack.removeLastI32().toUInt().toULong()
        val source = stack.removeLastI32().toUInt().toULong()
        val seg = instance.dataSegments.getOrNull(ins.dataSegment)
            ?: throw Trap(TrapKind.UNDEFINED_ELEMENT, "unknown data segment ${ins.dataSegment}")
        val mem = instance.memories[ins.memIndex]
        val destination = popIndex(stack, mem.indexType)
        if (
            source > seg.rawInit.size.toULong() ||
            count > seg.rawInit.size.toULong() - source ||
            destination > mem.byteSize.toULong() ||
            count > mem.byteSize.toULong() - destination
        ) {
            throw Trap.oobMemory(destination.saturatedLong(), count.saturatedInt())
        }
        val n = count.toInt()
        val s = source.toInt()
        val d = destination.toInt()
        seg.rawInit.copyInto(mem.data(), d, s, s + n)
    }

    // ---- memory load/store ----

    private fun execLoad(instance: Instance, ins: Load, stack: ArrayDeque<Value>) {
        val mem = instance.memories[ins.memoryIndex]
        val base = effectiveAddress(stack, mem, ins.offset)
        when (ins.opcode) {
            0x28 -> { mem.checkRange(base, 4); stack.addLast(Value.I32(loadI32(mem, base))) }       // i32.load
            0x29 -> { mem.checkRange(base, 8); stack.addLast(Value.I64(loadI64(mem, base))) }       // i64.load
            0x2A -> { mem.checkRange(base, 4); stack.addLast(Value.F32(Float.fromBits(loadI32(mem, base)))) }
            0x2B -> { mem.checkRange(base, 8); stack.addLast(Value.F64(Double.fromBits(loadI64(mem, base)))) }
            0x2C -> { mem.checkRange(base, 1); stack.addLast(Value.I32(mem.data()[base.toInt()].toInt())) } // i32.load8_s
            0x2D -> { mem.checkRange(base, 1); stack.addLast(Value.I32((mem.data()[base.toInt()].toInt() and 0xFF))) } // i32.load8_u
            0x2E -> { mem.checkRange(base, 2); stack.addLast(Value.I32(loadI16Signed(mem, base))) }
            0x2F -> { mem.checkRange(base, 2); stack.addLast(Value.I32(loadI16Unsigned(mem, base))) }
            0x30 -> { mem.checkRange(base, 1); stack.addLast(Value.I64(mem.data()[base.toInt()].toLong())) } // i64.load8_s
            0x31 -> { mem.checkRange(base, 1); stack.addLast(Value.I64(mem.data()[base.toInt()].toLong() and 0xFFL)) } // i64.load8_u
            0x32 -> { mem.checkRange(base, 2); stack.addLast(Value.I64(loadI16Signed(mem, base).toLong())) }
            0x33 -> { mem.checkRange(base, 2); stack.addLast(Value.I64(loadI16Unsigned(mem, base).toLong())) }
            0x34 -> { mem.checkRange(base, 4); stack.addLast(Value.I64(loadI32(mem, base).toLong())) } // i64.load32_s
            0x35 -> { mem.checkRange(base, 4); stack.addLast(Value.I64(loadI32(mem, base).toLong() and 0xFFFFFFFFL)) }
            else -> throw Trap(TrapKind.UNREACHABLE, "unsupported load opcode 0x${ins.opcode.toString(16)}")
        }
    }

    private fun execStore(instance: Instance, ins: Instr.Store, stack: ArrayDeque<Value>) {
        val v = stack.removeLast()
        val mem = instance.memories[ins.memoryIndex]
        val base = effectiveAddress(stack, mem, ins.offset)
        when (ins.opcode) {
            0x36 -> { mem.checkRange(base, 4); storeI32(mem, base, (v as Value.I32).v) }      // i32.store
            0x37 -> { mem.checkRange(base, 8); storeI64(mem, base, (v as Value.I64).v) }      // i64.store
            0x38 -> { mem.checkRange(base, 4); storeI32(mem, base, (v as Value.F32).v.toRawBits()) }
            0x39 -> { mem.checkRange(base, 8); storeI64(mem, base, (v as Value.F64).v.toRawBits()) }
            0x3A -> { mem.checkRange(base, 1); mem.data()[base.toInt()] = (v as Value.I32).v.toByte() }   // i32.store8
            0x3B -> { mem.checkRange(base, 2); storeI16(mem, base, (v as Value.I32).v) }
            0x3C -> { mem.checkRange(base, 1); mem.data()[base.toInt()] = (v as Value.I64).v.toByte() }
            0x3D -> { mem.checkRange(base, 2); storeI16(mem, base, (v as Value.I64).v.toInt()) }
            0x3E -> { mem.checkRange(base, 4); storeI32(mem, base, (v as Value.I64).v.toInt()) }
            else -> throw Trap(TrapKind.UNREACHABLE, "unsupported store opcode 0x${ins.opcode.toString(16)}")
        }
    }

    private fun loadI32(mem: MemoryInstance, addr: Long): Int {
        val a = addr.toInt()
        val b = mem.data()
        return (b[a].toInt() and 0xFF) or ((b[a + 1].toInt() and 0xFF) shl 8) or
            ((b[a + 2].toInt() and 0xFF) shl 16) or ((b[a + 3].toInt() and 0xFF) shl 24)
    }

    private fun loadI64(mem: MemoryInstance, addr: Long): Long {
        val a = addr.toInt()
        val b = mem.data()
        return (b[a].toLong() and 0xFF) or ((b[a + 1].toLong() and 0xFF) shl 8) or
            ((b[a + 2].toLong() and 0xFF) shl 16) or ((b[a + 3].toLong() and 0xFF) shl 24) or
            ((b[a + 4].toLong() and 0xFF) shl 32) or ((b[a + 5].toLong() and 0xFF) shl 40) or
            ((b[a + 6].toLong() and 0xFF) shl 48) or ((b[a + 7].toLong() and 0xFF) shl 56)
    }

    private fun loadI16Signed(mem: MemoryInstance, addr: Long): Int {
        val a = addr.toInt()
        val b = mem.data()
        val v = ((b[a].toInt() and 0xFF) or ((b[a + 1].toInt() and 0xFF) shl 8))
        return (v shl 16) shr 16 // sign extend
    }

    private fun loadI16Unsigned(mem: MemoryInstance, addr: Long): Int {
        val a = addr.toInt()
        val b = mem.data()
        return (b[a].toInt() and 0xFF) or ((b[a + 1].toInt() and 0xFF) shl 8)
    }

    private fun storeI32(mem: MemoryInstance, addr: Long, v: Int) {
        val a = addr.toInt()
        val b = mem.data()
        b[a] = v.toByte(); b[a + 1] = (v shr 8).toByte(); b[a + 2] = (v shr 16).toByte(); b[a + 3] = (v shr 24).toByte()
    }

    private fun storeI64(mem: MemoryInstance, addr: Long, v: Long) {
        val a = addr.toInt()
        val b = mem.data()
        for (k in 0 until 8) b[a + k] = (v shr (8 * k)).toByte()
    }

    private fun storeI16(mem: MemoryInstance, addr: Long, v: Int) {
        val a = addr.toInt()
        val b = mem.data()
        b[a] = v.toByte(); b[a + 1] = (v shr 8).toByte()
    }

    private fun popIndex(stack: ArrayDeque<Value>, type: IndexType): ULong = when (type) {
        IndexType.I32 -> stack.removeLastI32().toUInt().toULong()
        IndexType.I64 -> stack.removeLastI64().toULong()
    }

    private fun pushIndex(stack: ArrayDeque<Value>, type: IndexType, value: ULong) {
        when (type) {
            IndexType.I32 -> stack.addLast(Value.I32(value.toUInt().toInt()))
            IndexType.I64 -> stack.addLast(Value.I64(value.toLong()))
        }
    }

    private fun pushIndexResult(stack: ArrayDeque<Value>, type: IndexType, result: Int) {
        when (type) {
            IndexType.I32 -> stack.addLast(Value.I32(result))
            IndexType.I64 -> stack.addLast(Value.I64(result.toLong()))
        }
    }

    private fun minimumIndexType(left: IndexType, right: IndexType): IndexType =
        if (left == IndexType.I32 || right == IndexType.I32) IndexType.I32 else IndexType.I64

    private fun ULong.saturatedInt(): Int =
        if (this > Int.MAX_VALUE.toULong()) Int.MAX_VALUE else toInt()

    private fun ULong.saturatedLong(): Long =
        if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()

    private fun execMemorySize(
        instance: Instance,
        memoryIndex: Int,
        stack: ArrayDeque<Value>,
    ) {
        val memory = instance.memories[memoryIndex]
        when (memory.indexType) {
            IndexType.I32 -> stack.addLast(Value.I32(memory.sizeInPages.toInt()))
            IndexType.I64 -> stack.addLast(Value.I64(memory.sizeInPages.toLong()))
        }
    }

    private fun execMemoryGrow(
        instance: Instance,
        memoryIndex: Int,
        stack: ArrayDeque<Value>,
    ) {
        val memory = instance.memories[memoryIndex]
        when (memory.indexType) {
            IndexType.I32 -> stack.addLast(Value.I32(memory.grow(stack.removeLastI32())))
            IndexType.I64 -> {
                val requested = stack.removeLastI64().toULong()
                val result =
                    if (requested > Int.MAX_VALUE.toULong()) -1
                    else memory.grow(requested.toInt())
                stack.addLast(Value.I64(result.toLong()))
            }
        }
    }

    private fun effectiveAddress(
        stack: ArrayDeque<Value>,
        memory: MemoryInstance,
        offset: ULong,
    ): Long {
        val address = when (memory.indexType) {
            IndexType.I32 -> stack.removeLastI32().toUInt().toULong()
            IndexType.I64 -> stack.removeLastI64().toULong()
        }
        val effective = address + offset
        if (effective < address || effective > Long.MAX_VALUE.toULong()) {
            throw Trap.oobMemory(Long.MAX_VALUE, 1)
        }
        return effective.toLong()
    }

    // ---- numeric / comparison / arithmetic ----

    private fun execSimple(opcode: Int, stack: ArrayDeque<Value>) {
        when (opcode) {
            // i32 comparisons
            0x45 -> stack.addLast(Value.I32(if (stack.removeLastI32() == 0) 1 else 0))              // i32.eqz
            0x46 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a == b) 1 else 0)) }
            0x47 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a != b) 1 else 0)) }
            0x48 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a < b) 1 else 0)) }              // i32.lt_s
            0x49 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a.toUInt() < b.toUInt()) 1 else 0)) } // i32.lt_u
            0x4A -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a > b) 1 else 0)) }              // i32.gt_s
            0x4B -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a.toUInt() > b.toUInt()) 1 else 0)) } // i32.gt_u
            0x4C -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a <= b) 1 else 0)) }
            0x4D -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a.toUInt() <= b.toUInt()) 1 else 0)) }
            0x4E -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a >= b) 1 else 0)) }
            0x4F -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(if (a.toUInt() >= b.toUInt()) 1 else 0)) }
            // i64 comparisons
            0x50 -> stack.addLast(Value.I32(if (stack.removeLastI64() == 0L) 1 else 0))            // i64.eqz
            0x51 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a == b) 1 else 0)) }
            0x52 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a != b) 1 else 0)) }
            0x53 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a < b) 1 else 0)) }              // i64.lt_s
            0x54 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a.toULong() < b.toULong()) 1 else 0)) } // i64.lt_u
            0x55 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a > b) 1 else 0)) }              // i64.gt_s
            0x56 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a.toULong() > b.toULong()) 1 else 0)) } // i64.gt_u
            0x57 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a <= b) 1 else 0)) }
            0x58 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a.toULong() <= b.toULong()) 1 else 0)) }
            0x59 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a >= b) 1 else 0)) }
            0x5A -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I32(if (a.toULong() >= b.toULong()) 1 else 0)) }
            // f32 comparisons
            0x5B -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.I32(if (a == b) 1 else 0)) }
            0x5C -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.I32(if (a != b) 1 else 0)) }
            0x5D -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.I32(if (a < b) 1 else 0)) }
            0x5E -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.I32(if (a > b) 1 else 0)) }
            0x5F -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.I32(if (a <= b) 1 else 0)) }
            0x60 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.I32(if (a >= b) 1 else 0)) }
            // f64 comparisons
            0x61 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.I32(if (a == b) 1 else 0)) }
            0x62 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.I32(if (a != b) 1 else 0)) }
            0x63 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.I32(if (a < b) 1 else 0)) }
            0x64 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.I32(if (a > b) 1 else 0)) }
            0x65 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.I32(if (a <= b) 1 else 0)) }
            0x66 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.I32(if (a >= b) 1 else 0)) }
            // i32 arithmetic
            // i32 unary (counting)
            0x67 -> stack.addLast(Value.I32(clz32(stack.removeLastI32())))   // i32.clz
            0x68 -> stack.addLast(Value.I32(ctz32(stack.removeLastI32())))   // i32.ctz
            0x69 -> stack.addLast(Value.I32(popcnt32(stack.removeLastI32()))) // i32.popcnt
            // i32 arithmetic
            0x6A -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a + b)) }
            0x6B -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a - b)) }
            0x6C -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a * b)) }
            0x6D -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(idiv(a, b))) }
            0x6E -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(udiv(a, b))) }
            0x6F -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(irem(a, b))) }
            0x70 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(urem(a, b))) }
            0x71 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a and b)) }
            0x72 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a or b)) }
            0x73 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a xor b)) }
            0x74 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(ishl(a, b))) }
            0x75 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(isr(a, b))) }
            0x76 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(ushr(a, b))) }
            0x77 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(rotl(a, b))) }
            0x78 -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(rotr(a, b))) }
            // i64 arithmetic
            // i64 unary (counting)
            0x79 -> stack.addLast(Value.I64(clz64(stack.removeLastI64())))   // i64.clz
            0x7A -> stack.addLast(Value.I64(ctz64(stack.removeLastI64())))   // i64.ctz
            0x7B -> stack.addLast(Value.I64(popcnt64(stack.removeLastI64()))) // i64.popcnt
            // i64 arithmetic
            0x7C -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a + b)) }
            0x7D -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a - b)) }
            0x7E -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a * b)) }
            0x7F -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(ldiv(a, b))) }
            0x80 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(udiv64(a, b))) }
            0x81 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lrem(a, b))) }
            0x82 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lurem(a, b))) }
            0x83 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a and b)) }
            0x84 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a or b)) }
            0x85 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a xor b)) }
            0x86 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lshl(a, b))) }
            0x87 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lshr(a, b))) }
            0x88 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lushr(a, b))) }
            0x89 -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lrotl(a, b))) }
            0x8A -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(lrotr(a, b))) }
            // f32 arithmetic
            0x8B -> {
                val a = stack.removeLastF32()
                stack.addLast(Value.F32(Float.fromBits(a.toRawBits() and Int.MAX_VALUE)))
            }
            0x8C -> { val a = stack.removeLastF32(); stack.addLast(Value.F32(-a)) }
            0x8D -> { val a = stack.removeLastF32(); stack.addLast(Value.F32(quietNaN(ceil(a)))) }
            0x8E -> { val a = stack.removeLastF32(); stack.addLast(Value.F32(quietNaN(floor(a)))) }
            0x8F -> { val a = stack.removeLastF32(); stack.addLast(Value.F32(truncF(a))) }
            0x90 -> { val a = stack.removeLastF32(); stack.addLast(Value.F32(roundNearest(a))) }
            0x91 -> { val a = stack.removeLastF32(); stack.addLast(Value.F32(sqrt(a))) }
            0x92 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(a + b)) }
            0x93 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(a - b)) }
            0x94 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(a * b)) }
            0x95 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(a / b)) }
            0x96 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(fmin(a, b))) }
            0x97 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(fmax(a, b))) }
            0x98 -> { val b = stack.removeLastF32(); val a = stack.removeLastF32(); stack.addLast(Value.F32(copysign(a, b))) }
            // f64 arithmetic
            0x99 -> {
                val a = stack.removeLastF64()
                stack.addLast(Value.F64(Double.fromBits(a.toRawBits() and Long.MAX_VALUE)))
            }
            0x9A -> { val a = stack.removeLastF64(); stack.addLast(Value.F64(-a)) }
            0x9B -> { val a = stack.removeLastF64(); stack.addLast(Value.F64(quietNaN(ceil(a)))) }
            0x9C -> { val a = stack.removeLastF64(); stack.addLast(Value.F64(quietNaN(floor(a)))) }
            0x9D -> { val a = stack.removeLastF64(); stack.addLast(Value.F64(truncF(a))) }
            0x9E -> { val a = stack.removeLastF64(); stack.addLast(Value.F64(roundNearestDouble(a))) }
            0x9F -> { val a = stack.removeLastF64(); stack.addLast(Value.F64(sqrt(a))) }
            0xA0 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(a + b)) }
            0xA1 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(a - b)) }
            0xA2 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(a * b)) }
            0xA3 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(a / b)) }
            0xA4 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(fmin(a, b))) }
            0xA5 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(fmax(a, b))) }
            0xA6 -> { val b = stack.removeLastF64(); val a = stack.removeLastF64(); stack.addLast(Value.F64(copysign(a, b))) }
            // conversions (0xA7..0xBF) — verified against wasm-reference-manual
            0xA7 -> stack.addLast(Value.I32(stack.removeLastI64().toInt()))                                  // i32.wrap_i64
            0xA8 -> stack.addLast(Value.I32(truncF32ToI32S(stack.removeLastF32())))                          // i32.trunc_f32_s
            0xA9 -> stack.addLast(Value.I32(truncF32ToI32U(stack.removeLastF32())))                          // i32.trunc_f32_u
            0xAA -> stack.addLast(Value.I32(truncF64ToI32S(stack.removeLastF64())))                          // i32.trunc_f64_s
            0xAB -> stack.addLast(Value.I32(truncF64ToI32U(stack.removeLastF64())))                          // i32.trunc_f64_u
            0xAC -> stack.addLast(Value.I64(stack.removeLastI32().toLong()))                                 // i64.extend_i32_s
            0xAD -> stack.addLast(Value.I64(stack.removeLastI32().toLong() and 0xFFFFFFFFL))                 // i64.extend_i32_u
            0xAE -> stack.addLast(Value.I64(truncF32ToI64S(stack.removeLastF32())))                          // i64.trunc_f32_s
            0xAF -> stack.addLast(Value.I64(truncF32ToI64U(stack.removeLastF32())))                          // i64.trunc_f32_u
            0xB0 -> stack.addLast(Value.I64(truncF64ToI64S(stack.removeLastF64())))                          // i64.trunc_f64_s
            0xB1 -> stack.addLast(Value.I64(truncF64ToI64U(stack.removeLastF64())))                          // i64.trunc_f64_u
            0xB2 -> stack.addLast(Value.F32(stack.removeLastI32().toFloat()))                                // f32.convert_i32_s
            0xB3 -> stack.addLast(Value.F32(stack.removeLastI32().toUInt().toFloat()))                       // f32.convert_i32_u
            0xB4 -> stack.addLast(Value.F32(stack.removeLastI64().toFloat()))                                // f32.convert_i64_s
            0xB5 -> stack.addLast(Value.F32(unsignedLongBitsToFloat(stack.removeLastI64())))                 // f32.convert_i64_u
            0xB6 -> stack.addLast(Value.F32(stack.removeLastF64().toFloat()))                                // f32.demote_f64
            0xB7 -> stack.addLast(Value.F64(stack.removeLastI32().toDouble()))                               // f64.convert_i32_s
            0xB8 -> stack.addLast(Value.F64(stack.removeLastI32().toUInt().toDouble()))                      // f64.convert_i32_u
            0xB9 -> stack.addLast(Value.F64(stack.removeLastI64().toDouble()))                               // f64.convert_i64_s
            0xBA -> stack.addLast(Value.F64(stack.removeLastI64().toULong().toDouble()))                     // f64.convert_i64_u
            0xBB -> stack.addLast(Value.F64(stack.removeLastF32().toDouble()))                               // f64.promote_f32
            0xBC -> stack.addLast(Value.I32((stack.removeLast() as Value.F32).v.toRawBits()))                // i32.reinterpret_f32
            0xBD -> stack.addLast(Value.I64((stack.removeLast() as Value.F64).v.toRawBits()))                // i64.reinterpret_f64
            0xBE -> stack.addLast(Value.F32(Float.fromBits(stack.removeLastI32())))                          // f32.reinterpret_i32
            0xBF -> stack.addLast(Value.F64(Double.fromBits(stack.removeLastI64())))                         // f64.reinterpret_i64
            // sign-extension proposal (0xC0..0xC4)
            0xC0 -> { val a = stack.removeLastI32(); stack.addLast(Value.I32(((a shl 24) shr 24))) }          // i32.extend8_s
            0xC1 -> { val a = stack.removeLastI32(); stack.addLast(Value.I32(((a shl 16) shr 16))) }          // i32.extend16_s
            0xC2 -> { val a = stack.removeLastI64(); stack.addLast(Value.I64(((a shl 56) shr 56))) }          // i64.extend8_s
            0xC3 -> { val a = stack.removeLastI64(); stack.addLast(Value.I64(((a shl 48) shr 48))) }          // i64.extend16_s
            0xC4 -> { val a = stack.removeLastI64(); stack.addLast(Value.I64(((a shl 32) shr 32))) }          // i64.extend32_s
            else -> throw Trap(TrapKind.UNREACHABLE, "unsupported opcode 0x${opcode.toString(16)}")
        }
    }
}

/*
 * Prevent the withContext boundary used by the execution gate from applying
 * coroutine stack-trace recovery directly to an embedder's exception. The
 * deliberately non-standard constructor shape keeps this private carrier from
 * being copied; the public boundary then rethrows the exact host object.
 */
private class HostImportFailure(
    val original: Throwable,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : RuntimeException()

private fun Instr.requiresCallCheckpoint(): Boolean =
    this is Call ||
        this is CallIndirect ||
        this is ReturnCall ||
        this is ReturnCallIndirect ||
        this is CallRef ||
        this is ReturnCallRef

private class GuestThrown(val exception: GuestException) : Exception()
