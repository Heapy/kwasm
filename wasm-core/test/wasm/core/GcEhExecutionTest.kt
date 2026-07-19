package io.heapy.kwasm

import io.heapy.kwasm.Instr.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GcEhExecutionTest {
    @Test
    fun gcArrayAndI31InstructionsExecute(): Unit = runBlocking {
        val module = validatedModule {
            types += ArrayType(FieldType(StorageType.Value(ValType.I32), Mutability.VAR))
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 1,
                locals = emptyList(),
                body = listOf(
                    I32Const(7),
                    I32Const(3),
                    Gc(6, firstIndex = 0),
                    I32Const(1),
                    Gc(11, firstIndex = 0),
                    Gc(28),
                    Gc(29),
                ),
            )
        }

        assertEquals(listOf(Value.I32(7)), Interpreter().invoke(Instance(module), 0))
    }

    @Test
    fun standardizedTryTableCatchesTypedGuestException(): Unit = runBlocking {
        val module = exceptionModule { tagIndex ->
            TryTable(
                blockType = BlockType.Single(ValType.I32),
                catches = listOf(CatchClause.Tagged(tagIndex, depth = 0, withReference = false)),
                body = listOf(I32Const(42), Throw(tagIndex)),
            )
        }

        assertEquals(listOf(Value.I32(42)), Interpreter().invoke(Instance(module), 0))
    }

    @Test
    fun tryTableCatchRefBranchesToTheOuterLabelWithANonNullException(): Unit = runBlocking {
        val nonNullException = RefType(HeapType.Exn, nullable = false)
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            types += FuncType(emptyList(), listOf(ValType.I32))
            tags += Tag(typeIndex = 0)
            functions += Function(
                typeIndex = 1,
                locals = emptyList(),
                body = listOf(
                    Block(
                        blockType = BlockType.Single(nonNullException),
                        body = listOf(
                            TryTable(
                                blockType = BlockType.Empty,
                                catches = listOf(
                                    CatchClause.All(
                                        depth = 0,
                                        withReference = true,
                                    ),
                                ),
                                body = listOf(Throw(0)),
                            ),
                            Unreachable,
                        ),
                    ),
                    RefIsNull,
                ),
            )
        }

        assertEquals(
            listOf(Value.I32(0)),
            Interpreter().invoke(Instance(module), 0),
        )
    }

    @Test
    fun legacyTryCatchExecutesCatchBody(): Unit = runBlocking {
        val module = exceptionModule { tagIndex ->
            LegacyTry(
                blockType = BlockType.Single(ValType.I32),
                body = listOf(I32Const(23), Throw(tagIndex)),
                catches = listOf(LegacyCatch(tagIndex, emptyList())),
                catchAll = null,
                delegateDepth = null,
            )
        }

        assertEquals(listOf(Value.I32(23)), Interpreter().invoke(Instance(module), 0))
    }

    @Test
    fun legacyCatchReplacesTheTryBodyHotPlan(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            types += FuncType(emptyList(), listOf(ValType.I32))
            tags += Tag(typeIndex = 0)
            functions += Function(
                typeIndex = 1,
                locals = listOf(ValType.I32),
                body = listOf(
                    LegacyTry(
                        blockType = BlockType.Single(ValType.I32),
                        body = listOf(
                            FcIndex(0x20, 0),
                            I32Const(1),
                            Simple(0x6A),
                            Drop(),
                            Throw(0),
                        ),
                        catches = listOf(
                            LegacyCatch(
                                tagIndex = 0,
                                body = listOf(
                                    FcIndex(0x20, 0),
                                    I32Const(1),
                                    If(
                                        blockType = BlockType.Empty,
                                        thenBody = emptyList(),
                                        elseBody = emptyList(),
                                    ),
                                ),
                            ),
                        ),
                        catchAll = null,
                        delegateDepth = null,
                    ),
                ),
            )
        }

        assertEquals(listOf(Value.I32(0)), Interpreter().invoke(Instance(module), 0))
    }

    @Test
    fun nullReferenceBranchesUseTheirSpecifiedFallthroughStack(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I32, FuncRefType))
            types += FuncType(emptyList(), listOf(ValType.I32, ValType.I32))
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                1,
                emptyList(),
                listOf(
                    Block(
                        BlockType.TypeIndex(0),
                        listOf(
                            I32Const(42),
                            RefNull(HeapType.Func),
                            BrOnNonNull(0),
                            RefNull(HeapType.Func),
                        ),
                    ),
                    RefIsNull,
                ),
            )
            functions += Function(
                2,
                emptyList(),
                listOf(
                    Block(
                        BlockType.Single(ValType.I32),
                        listOf(
                            I32Const(23),
                            RefNull(HeapType.Func),
                            BrOnNull(0),
                            Drop(),
                        ),
                    ),
                ),
            )
        }

        assertEquals(
            listOf(Value.I32(42), Value.I32(1)),
            Interpreter().invoke(Instance(module), 0),
        )
        assertEquals(listOf(Value.I32(23)), Interpreter().invoke(Instance(module), 1))
    }

    @Test
    fun indexedReferenceCastsAndCastBranchesExecute(): Unit = runBlocking {
        val source = RefType(HeapType.Index(0), nullable = true)
        val cast = RefType(HeapType.Index(1), nullable = false)
        val module = validatedModule {
            types += StructType(emptyList(), isFinal = false)
            types += StructType(emptyList(), supertypes = listOf(0))
            types += FuncType(emptyList(), listOf(ValType.I32, cast))
            types += FuncType(emptyList(), listOf(ValType.I32, source))
            types += FuncType(
                emptyList(),
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
            )
            functions += Function(
                4,
                listOf(source),
                listOf(
                    Gc(0, firstIndex = 1),
                    FcIndex(0x21, 0),
                    FcIndex(0x20, 0),
                    Gc(20, targetType = cast),
                    FcIndex(0x20, 0),
                    Gc(22, targetType = cast),
                    RefIsNull,
                    Block(
                        BlockType.TypeIndex(2),
                        listOf(
                            I32Const(7),
                            FcIndex(0x20, 0),
                            Gc(24, depth = 0, sourceType = source, targetType = cast),
                            Drop(),
                            Drop(),
                            I32Const(9),
                            Gc(0, firstIndex = 1),
                        ),
                    ),
                    Drop(),
                    Gc(0, firstIndex = 0),
                    FcIndex(0x21, 0),
                    Block(
                        BlockType.TypeIndex(3),
                        listOf(
                            I32Const(11),
                            FcIndex(0x20, 0),
                            Gc(25, depth = 0, sourceType = source, targetType = cast),
                            Drop(),
                            Drop(),
                            I32Const(13),
                            Gc(0, firstIndex = 0),
                        ),
                    ),
                    Drop(),
                ),
            )
        }

        assertEquals(
            listOf(Value.I32(1), Value.I32(0), Value.I32(7), Value.I32(11)),
            Interpreter().invoke(Instance(module), 0),
        )
    }

    @Test
    fun legacyRethrowDepthCountsInterveningLabels(): Unit = runBlocking {
        val module = exceptionModule { tagIndex ->
            LegacyTry(
                blockType = BlockType.Single(ValType.I32),
                body = listOf(
                    LegacyTry(
                        blockType = BlockType.Single(ValType.I32),
                        body = listOf(I32Const(42), Throw(tagIndex)),
                        catches = listOf(
                            LegacyCatch(
                                tagIndex,
                                listOf(Block(BlockType.Empty, listOf(Rethrow(1)))),
                            ),
                        ),
                        catchAll = null,
                        delegateDepth = null,
                    ),
                ),
                catches = listOf(LegacyCatch(tagIndex, emptyList())),
                catchAll = null,
                delegateDepth = null,
            )
        }

        assertEquals(listOf(Value.I32(42)), Interpreter().invoke(Instance(module), 0))
    }

    @Test
    fun linkedModulesShareNominalTagIdentity(): Unit = runBlocking {
        val store = Store()
        val producerModule = validatedModule {
            types += FuncType(listOf(ValType.I32), emptyList())
            types += FuncType(emptyList(), listOf(ValType.I32))
            tags += Tag(0)
            functions += Function(
                1,
                emptyList(),
                listOf(I32Const(77), Throw(0)),
            )
            exports += Export("tag", ExportDesc.Tag(0))
            exports += Export("throw", ExportDesc.Function(0))
        }
        val producer = Instance(store, producerModule, ResolvedImports())
        val linker = Linker().defineInstance("producer", producer)
        val consumerModule = validatedModule {
            types += FuncType(listOf(ValType.I32), emptyList())
            types += FuncType(emptyList(), listOf(ValType.I32))
            imports += Import("producer", "tag", ImportDesc.Tag(0, 0))
            imports += Import("producer", "throw", ImportDesc.Function(1))
            functions += Function(
                1,
                emptyList(),
                listOf(
                    TryTable(
                        BlockType.Single(ValType.I32),
                        listOf(CatchClause.Tagged(0, depth = 0, withReference = false)),
                        listOf(Call(0)),
                    ),
                ),
            )
            exports += Export("run", ExportDesc.Function(1))
        }
        val consumer = linker.instantiate(consumerModule, store)

        assertEquals(listOf(Value.I32(77)), consumer.invoke("run"))
    }

    @Test
    fun guestCatchAllCannotCatchHostExceptions(): Unit = runBlocking {
        val hostFailure = IllegalStateException("host bug")
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            imports += Import("host", "fail", ImportDesc.Function(0))
            functions += Function(
                0,
                emptyList(),
                listOf(
                    LegacyTry(
                        blockType = BlockType.Empty,
                        body = listOf(Call(0)),
                        catches = emptyList(),
                        catchAll = emptyList(),
                        delegateDepth = null,
                    ),
                ),
            )
            exports += Export("run", ExportDesc.Function(1))
        }
        val instance = Instance(
            Store(),
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        throw hostFailure
                    },
                ),
            ),
        )

        val escaped = assertFailsWith<IllegalStateException> {
            instance.invoke("run")
        }
        assertEquals(hostFailure, escaped)
    }

    @Test
    fun tryTableCatchAllCannotCatchHostExceptions(): Unit = runBlocking {
        val hostFailure = IllegalStateException("host bug")
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            imports += Import("host", "fail", ImportDesc.Function(0))
            functions += Function(
                0,
                emptyList(),
                listOf(
                    TryTable(
                        blockType = BlockType.Empty,
                        catches = listOf(CatchClause.All(depth = 0, withReference = false)),
                        body = listOf(Call(0)),
                    ),
                ),
            )
            exports += Export("run", ExportDesc.Function(1))
        }
        val instance = Instance(
            Store(),
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        throw hostFailure
                    },
                ),
            ),
        )

        val escaped = assertFailsWith<IllegalStateException> {
            instance.invoke("run")
        }
        assertEquals(hostFailure, escaped)
    }

    private fun exceptionModule(body: (Int) -> Instr): Module = validatedModule {
        types += FuncType(listOf(ValType.I32), emptyList())
        types += FuncType(emptyList(), listOf(ValType.I32))
        tags += Tag(typeIndex = 0)
        functions += Function(1, emptyList(), listOf(body(0)))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(ByteArray(0)).also(ModuleValidator::validate)
}
