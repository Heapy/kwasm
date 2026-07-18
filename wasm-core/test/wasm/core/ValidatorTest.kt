package io.heapy.kwasm

import io.heapy.kwasm.Instr.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ValidatorTest {
    @Test
    fun validatesTypedFunctionAndStructuredControl() {
        val module = module {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    I32Const(1),
                    If(
                        BlockType.Single(ValType.I32),
                        thenBody = listOf(I32Const(2)),
                        elseBody = listOf(I32Const(3)),
                    ),
                ),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun rejectsStackTypeMismatch() {
        val module = module {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(0, emptyList(), listOf(I64Const(1)))
        }

        val failure = assertFailsWith<InvalidModule> { ModuleValidator.validate(module) }
        assertEquals(0, failure.functionIndex)
    }

    @Test
    fun validatesBranchLabelTypesAndDepths() {
        val valid = module {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                0,
                emptyList(),
                listOf(Block(BlockType.Single(ValType.I32), listOf(I32Const(7), Br(0)))),
            )
        }
        ModuleValidator.validate(valid)

        val invalid = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(Br(1)))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(invalid) }
    }

    @Test
    fun validatesReferenceBranchStackShapes() {
        val blockResults = FuncType(emptyList(), listOf(ValType.I32, FuncRefType))
        val valid = module {
            types += blockResults
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                1,
                emptyList(),
                listOf(
                    Block(
                        BlockType.TypeIndex(0),
                        listOf(
                            I32Const(7),
                            RefNull(HeapType.Func),
                            BrOnNonNull(0),
                            RefNull(HeapType.Func),
                        ),
                    ),
                    Drop(),
                    Drop(),
                    Block(
                        BlockType.Single(ValType.I32),
                        listOf(
                            I32Const(8),
                            RefNull(HeapType.Func),
                            BrOnNull(0),
                            Drop(),
                        ),
                    ),
                    Drop(),
                ),
            )
        }
        ModuleValidator.validate(valid)

        val nonReferenceLabel = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                emptyList(),
                listOf(
                    Block(
                        BlockType.Single(ValType.I32),
                        listOf(I32Const(0), RefNull(HeapType.Func), BrOnNonNull(0)),
                    ),
                ),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(nonReferenceLabel) }
    }

    @Test
    fun validatesIndexedReferenceCastTypesAndRelationships() {
        val baseRef = RefType(HeapType.Index(0), nullable = true)
        val derivedRef = RefType(HeapType.Index(1), nullable = false)
        val valid = module {
            types += StructType(emptyList(), isFinal = false)
            types += StructType(emptyList(), supertypes = listOf(0))
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                2,
                listOf(baseRef),
                listOf(
                    Gc(0, firstIndex = 1),
                    FcIndex(0x21, 0),
                    FcIndex(0x20, 0),
                    Gc(20, targetType = derivedRef),
                    Drop(),
                    FcIndex(0x20, 0),
                    Gc(22, targetType = derivedRef),
                    Drop(),
                    RefNull(HeapType.None),
                    Gc(21, targetType = baseRef),
                    Drop(),
                    RefNull(HeapType.Index(1)),
                    Gc(
                        20,
                        targetType = RefType(HeapType.Index(0), nullable = false),
                    ),
                    Drop(),
                ),
            )
        }
        ModuleValidator.validate(valid)

        val unknownTarget = module {
            types += StructType(emptyList())
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                1,
                emptyList(),
                listOf(
                    RefNull(HeapType.Index(0)),
                    Gc(20, targetType = RefType(HeapType.Index(99), nullable = false)),
                    Drop(),
                ),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(unknownTarget) }

        val crossHierarchyCast = module {
            types += StructType(emptyList())
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                1,
                emptyList(),
                listOf(
                    RefNull(HeapType.Index(0)),
                    Gc(
                        20,
                        targetType = RefType(HeapType.Func, nullable = false),
                    ),
                    Drop(),
                ),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(crossHierarchyCast) }
    }

    @Test
    fun declaredSupertypeMayBeAnEarlierMemberOfTheSameRecursionGroup() {
        val valid = module {
            types += StructType(emptyList(), isFinal = false)
            types += StructType(emptyList(), supertypes = listOf(0))
            recursionGroups += RecursionGroup(startTypeIndex = 0, size = 2)
        }
        ModuleValidator.validate(valid)

        val forwardSupertype = module {
            types += StructType(emptyList(), supertypes = listOf(1))
            types += StructType(emptyList(), isFinal = false)
            recursionGroups += RecursionGroup(startTypeIndex = 0, size = 2)
        }
        assertFailsWith<InvalidModule> {
            ModuleValidator.validate(forwardSupertype)
        }
    }

    @Test
    fun validatesCastBranchSourceAndLabelTypes() {
        val source = RefType(HeapType.Index(0), nullable = true)
        val cast = RefType(HeapType.Index(1), nullable = false)
        val valid = module {
            types += StructType(emptyList(), isFinal = false)
            types += StructType(emptyList(), supertypes = listOf(0))
            types += FuncType(emptyList(), listOf(ValType.I32, cast))
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                3,
                emptyList(),
                listOf(
                    Block(
                        BlockType.TypeIndex(2),
                        listOf(
                            I32Const(1),
                            RefNull(HeapType.Index(0)),
                            Gc(24, depth = 0, sourceType = source, targetType = cast),
                            Drop(),
                            Drop(),
                            I32Const(0),
                            Gc(0, firstIndex = 1),
                        ),
                    ),
                    Drop(),
                    Drop(),
                ),
            )
        }
        ModuleValidator.validate(valid)

        val incompatibleSource = module {
            types += StructType(emptyList(), isFinal = false)
            types += StructType(emptyList(), supertypes = listOf(0))
            types += FuncType(emptyList(), listOf(cast))
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                3,
                emptyList(),
                listOf(
                    Block(
                        BlockType.TypeIndex(2),
                        listOf(
                            RefNull(HeapType.Index(1)),
                            Gc(
                                24,
                                depth = 0,
                                sourceType = RefType(HeapType.Index(1), nullable = true),
                                targetType = RefType(HeapType.Index(0), nullable = false),
                            ),
                        ),
                    ),
                    Drop(),
                ),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(incompatibleSource) }

        val nonReferenceLabel = module {
            types += StructType(emptyList())
            types += FuncType(emptyList(), listOf(ValType.I32))
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                2,
                emptyList(),
                listOf(
                    Block(
                        BlockType.TypeIndex(1),
                        listOf(
                            RefNull(HeapType.Index(0)),
                            Gc(
                                24,
                                depth = 0,
                                sourceType = RefType(HeapType.Index(0), nullable = true),
                                targetType = RefType(HeapType.Index(0), nullable = false),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(nonReferenceLabel) }
    }

    @Test
    fun rethrowMustTargetAnEnclosingLegacyCatchLabel() {
        val valid = legacyRethrowModule(rethrowDepth = 1)
        ModuleValidator.validate(valid)

        val nestedBlockInsteadOfCatch = legacyRethrowModule(rethrowDepth = 0)
        assertFailsWith<InvalidModule> { ModuleValidator.validate(nestedBlockInsteadOfCatch) }

        val outsideCatch = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(Rethrow(0)))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(outsideCatch) }
    }

    @Test
    fun enforcesTypeFunctionLocalAndExactByteLimits() {
        val module = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, listOf(ValType.I32, ValType.I64), emptyList())
        }

        val moduleSize = assertFailsWith<LimitExceeded> {
            ModuleValidator.validate(module, ModuleValidationLimits(maxModuleSizeBytes = 7))
        }
        assertEquals("module size", moduleSize.limit)

        val locals = assertFailsWith<LimitExceeded> {
            ModuleValidator.validate(module, ModuleValidationLimits(maxLocalsPerFunction = 1))
        }
        assertEquals("locals per function", locals.limit)

        val functions = assertFailsWith<LimitExceeded> {
            ModuleValidator.validate(module, ModuleValidationLimits(maxFunctions = 0))
        }
        assertEquals("functions", functions.limit)

        val body = assertFailsWith<LimitExceeded> {
            ModuleValidator.validate(module, ModuleValidationLimits(maxFunctionBodySizeBytes = 3))
        }
        assertEquals("function body size", body.limit)
    }

    @Test
    fun enforcesMemoryTableGlobalAndTypeLimits() {
        val module = module {
            types += FuncType(emptyList(), emptyList())
            memories += Memory(MemoryType(Limits(2u, 3u)))
            tables += Table(TableType(FuncRefType, Limits(4u, 5u)))
            globals += Global(GlobalType(ValType.I32, Mutability.CONST), listOf(I32Const(0)))
        }

        assertEquals(
            "memory pages",
            assertFailsWith<LimitExceeded> {
                ModuleValidator.validate(module, ModuleValidationLimits(maxMemoryPages = 1))
            }.limit,
        )
        assertEquals(
            "table elements",
            assertFailsWith<LimitExceeded> {
                ModuleValidator.validate(module, ModuleValidationLimits(maxTableElements = 3))
            }.limit,
        )
        assertEquals(
            "globals",
            assertFailsWith<LimitExceeded> {
                ModuleValidator.validate(module, ModuleValidationLimits(maxGlobals = 0))
            }.limit,
        )
        assertEquals(
            "types",
            assertFailsWith<LimitExceeded> {
                ModuleValidator.validate(module, ModuleValidationLimits(maxTypes = 0))
            }.limit,
        )
    }

    @Test
    fun validatesIndicesExportsAndStartType() {
        val badTypeIndex = module {
            functions += Function(0, emptyList(), emptyList())
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(badTypeIndex) }

        val duplicateExport = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), emptyList())
            exports += Export("same", ExportDesc.Function(0))
            exports += Export("same", ExportDesc.Function(0))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(duplicateExport) }

        val badStart = module {
            types += FuncType(listOf(ValType.I32), emptyList())
            functions += Function(0, emptyList(), emptyList())
            startFunction = 0
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(badStart) }
    }

    @Test
    fun validatesConstantExpressions() {
        val wrongInitializerType = module {
            globals += Global(GlobalType(ValType.I64, Mutability.CONST), listOf(I32Const(0)))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(wrongInitializerType) }

        val mutableImportedGlobalGet = module {
            imports += Import(
                "host",
                "mutable",
                ImportDesc.Global(GlobalType(ValType.I32, Mutability.VAR)),
            )
            globals += Global(
                GlobalType(ValType.I32, Mutability.CONST),
                listOf(FcIndex(0x23, 0)),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(mutableImportedGlobalGet) }

        val extendedConst = module {
            globals += Global(
                GlobalType(ValType.I32, Mutability.CONST),
                listOf(I32Const(1), I32Const(2), Simple(0x6A)),
            )
        }
        ModuleValidator.validate(extendedConst)

        val precedingImmutableGlobal = module {
            globals += Global(GlobalType(ValType.I32, Mutability.CONST), listOf(I32Const(1)))
            globals += Global(GlobalType(ValType.I32, Mutability.CONST), listOf(FcIndex(0x23, 0)))
        }
        ModuleValidator.validate(precedingImmutableGlobal)
    }

    @Test
    fun validatesAndEvaluatesWasm3GcConstantExpressions() {
        val nullableExtern = RefType(HeapType.Extern, nullable = true)
        val module = module {
            types += StructType(
                fields = listOf(
                    FieldType(StorageType.I8, Mutability.CONST),
                    FieldType(StorageType.Value(nullableExtern), Mutability.CONST),
                ),
            )
            types += StructType(
                fields = listOf(
                    FieldType(StorageType.Value(ValType.I32), Mutability.CONST),
                    FieldType(StorageType.Value(nullableExtern), Mutability.CONST),
                ),
            )
            types += ArrayType(FieldType(StorageType.I16, Mutability.CONST))

            globals += Global(
                GlobalType(nullableExtern, Mutability.CONST),
                listOf(RefNull(HeapType.Extern)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.Any, nullable = true), Mutability.CONST),
                listOf(FcIndex(0x23, 0), Gc(26)),
            )
            globals += Global(
                GlobalType(nullableExtern, Mutability.CONST),
                listOf(FcIndex(0x23, 1), Gc(27)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.I31, nullable = false), Mutability.CONST),
                listOf(I32Const(-1), Gc(28)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.Index(0), nullable = false), Mutability.CONST),
                listOf(I32Const(0x123), FcIndex(0x23, 0), Gc(0, firstIndex = 0)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.Index(1), nullable = false), Mutability.CONST),
                listOf(Gc(1, firstIndex = 1)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.Index(2), nullable = false), Mutability.CONST),
                listOf(I32Const(0x1_2345), I32Const(2), Gc(6, firstIndex = 2)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.Index(2), nullable = false), Mutability.CONST),
                listOf(I32Const(3), Gc(7, firstIndex = 2)),
            )
            globals += Global(
                GlobalType(RefType(HeapType.Index(2), nullable = false), Mutability.CONST),
                listOf(
                    I32Const(1),
                    I32Const(2),
                    Gc(8, firstIndex = 2, count = 2),
                ),
            )
        }

        ModuleValidator.validate(module)
        val instance = Instance(module)

        assertEquals(Value.NULL_GC, instance.globals[1].value)
        assertEquals(Value.NULL_EXTERN, instance.globals[2].value)
        assertEquals(Value.Ref.I31(-1), instance.globals[3].value)

        val struct = assertIs<StructObject>(
            assertIs<Value.Ref.Gc>(instance.globals[4].value).value,
        )
        assertEquals(listOf(Value.I32(0x23), Value.NULL_EXTERN), struct.fields)

        val defaultStruct = assertIs<StructObject>(
            assertIs<Value.Ref.Gc>(instance.globals[5].value).value,
        )
        assertEquals(listOf(Value.I32(0), Value.NULL_EXTERN), defaultStruct.fields)

        val repeated = assertIs<ArrayObject>(
            assertIs<Value.Ref.Gc>(instance.globals[6].value).value,
        )
        assertEquals(
            listOf<Value>(Value.I32(0x2345), Value.I32(0x2345)),
            repeated.elements,
        )

        val defaultArray = assertIs<ArrayObject>(
            assertIs<Value.Ref.Gc>(instance.globals[7].value).value,
        )
        assertEquals(List<Value>(3) { Value.I32(0) }, defaultArray.elements)

        val fixed = assertIs<ArrayObject>(
            assertIs<Value.Ref.Gc>(instance.globals[8].value).value,
        )
        assertEquals(listOf<Value>(Value.I32(1), Value.I32(2)), fixed.elements)

        val nonConstantGcInstruction = module {
            types += ArrayType(FieldType(StorageType.I8, Mutability.CONST))
            globals += Global(
                GlobalType(RefType(HeapType.Index(0), nullable = false), Mutability.CONST),
                listOf(
                    I32Const(0),
                    I32Const(0),
                    Gc(9, firstIndex = 0, secondIndex = 0),
                ),
            )
        }
        assertFailsWith<InvalidModule> {
            ModuleValidator.validate(nonConstantGcInstruction)
        }
    }

    @Test
    fun validatesDataCountAndBulkMemoryIndices() {
        val mismatch = module {
            dataCount = 0
            dataSegments += DataSegment(DataMode.Passive, byteArrayOf(1))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(mismatch) }

        val missingDataCount = module {
            types += FuncType(emptyList(), emptyList())
            memories += Memory(MemoryType(Limits(1u, 1u)))
            dataSegments += DataSegment(DataMode.Passive, byteArrayOf(1))
            functions += Function(
                0,
                emptyList(),
                listOf(I32Const(0), I32Const(0), I32Const(0), MemoryInit(0, 0)),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(missingDataCount) }

        val missingDataCountForGc = module {
            val arrayRef = RefType(HeapType.Index(0), nullable = false)
            types += ArrayType(FieldType(StorageType.I8, Mutability.VAR))
            types += FuncType(emptyList(), listOf(arrayRef))
            dataSegments += DataSegment(DataMode.Passive, byteArrayOf(1))
            functions += Function(
                1,
                emptyList(),
                listOf(I32Const(0), I32Const(0), Gc(9, firstIndex = 0, secondIndex = 0)),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(missingDataCountForGc) }
    }

    @Test
    fun rejectsSimdV128AndThreadsAtValidationTime() {
        val v128 = module {
            types += FuncType(listOf(ValType.V128), emptyList())
        }
        assertEquals("simd", assertFailsWith<UnsupportedFeature> { ModuleValidator.validate(v128) }.feature)
        ModuleValidator.validate(
            v128,
            ModuleValidationLimits(allowInertV128Types = true),
        )

        val simdOpcode = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(RawImmediate(0xFD, longArrayOf(0), byteArrayOf())))
        }
        assertEquals("simd", assertFailsWith<UnsupportedFeature> { ModuleValidator.validate(simdOpcode) }.feature)
        assertEquals(
            "simd",
            assertFailsWith<UnsupportedFeature> {
                ModuleValidator.validate(
                    simdOpcode,
                    ModuleValidationLimits(allowInertV128Types = true),
                )
            }.feature,
        )

        val atomicOpcode = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(RawImmediate(0xFE, longArrayOf(0), byteArrayOf())))
        }
        assertEquals("threads", assertFailsWith<UnsupportedFeature> { ModuleValidator.validate(atomicOpcode) }.feature)

        val sharedMemory = module {
            memories += Memory(MemoryType(Limits(1u, 1u, shared = true)))
        }
        assertEquals("threads", assertFailsWith<UnsupportedFeature> { ModuleValidator.validate(sharedMemory) }.feature)
    }

    @Test
    fun acceptsMixedMemory64AndTable64CopySignatures() {
        val module = module {
            types += FuncType(emptyList(), emptyList())
            memories += Memory(MemoryType(Limits(0u, 1u, indexType = IndexType.I32)))
            memories += Memory(MemoryType(Limits(0u, 1u, indexType = IndexType.I64)))
            tables += Table(TableType(FuncRefType, Limits(0u, 1u, indexType = IndexType.I32)))
            tables += Table(TableType(FuncRefType, Limits(0u, 1u, indexType = IndexType.I64)))
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I32Const(0),
                    I64Const(0),
                    I32Const(0),
                    MemoryCopy(0, 1),
                    I32Const(0),
                    I64Const(0),
                    I32Const(0),
                    FcIndex(0x0EFC, 0, 1),
                ),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun validatesSelectedMemoryAndWideOffsets() {
        val valid = module {
            types += FuncType(emptyList(), emptyList())
            memories += Memory(MemoryType(Limits(0u, 1u, indexType = IndexType.I32)))
            memories += Memory(MemoryType(Limits(0u, 1u, indexType = IndexType.I64)))
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I64Const(0),
                    Load(0x28, 2, 0uL, memoryIndex = 1),
                    Drop(),
                    MemorySizeAt(1),
                    Drop(),
                    I64Const(0),
                    MemoryGrowAt(1),
                    Drop(),
                ),
            )
        }
        ModuleValidator.validate(valid)

        val badOffset = module {
            types += FuncType(emptyList(), emptyList())
            memories += Memory(MemoryType(Limits(0u, 1u)))
            functions += Function(
                0,
                emptyList(),
                listOf(I32Const(0), Load(0x28, 2, UInt.MAX_VALUE.toULong() + 1uL), Drop()),
            )
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(badOffset) }
    }

    @Test
    fun tableInitAcceptsEveryElementMode() {
        val module = module {
            types += FuncType(emptyList(), emptyList())
            tables += Table(TableType(FuncRefType, Limits(1u, 1u)))
            elementSegments += ElementSegment(
                tableIndex = 0,
                offset = listOf(I32Const(0)),
                mode = ElementMode.Active(0, listOf(I32Const(0))),
                exprs = emptyList(),
                activeType = FuncRefType,
            )
            elementSegments += ElementSegment(
                tableIndex = -1,
                offset = null,
                mode = ElementMode.Declarative(FuncRefType),
                exprs = emptyList(),
                activeType = FuncRefType,
            )
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I32Const(0), I32Const(0), I32Const(0), FcIndex(0x0CFC, 0, 0),
                    I32Const(0), I32Const(0), I32Const(0), FcIndex(0x0CFC, 1, 0),
                ),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun enforcesDeclaredFunctionReferences() {
        val undeclared = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(RefFunc(0), Drop()))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(undeclared) }

        val declaredByExport = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(RefFunc(0), Drop()))
            exports += Export("self", ExportDesc.Function(0))
        }
        ModuleValidator.validate(declaredByExport)

        val declaredByTableInitializer = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), emptyList())
            tables += Table(
                TableType(RefType(HeapType.Func, nullable = false), Limits(1uL, 1uL)),
                init = listOf(RefFunc(0)),
            )
        }
        ModuleValidator.validate(declaredByTableInitializer)
    }

    @Test
    fun tracksNonNullLocalInitializationAcrossControlFlow() {
        val nonNullFuncRef = RefType(HeapType.Func, nullable = false)
        val uninitialized = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, listOf(nonNullFuncRef), listOf(FcIndex(0x20, 0), Drop()))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(uninitialized) }

        val initialized = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                listOf(nonNullFuncRef),
                listOf(RefFunc(0), FcIndex(0x21, 0), FcIndex(0x20, 0), Drop()),
            )
            exports += Export("self", ExportDesc.Function(0))
        }
        ModuleValidator.validate(initialized)

        val skippedByBranch = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                listOf(nonNullFuncRef),
                listOf(
                    Block(
                        BlockType.Empty,
                        listOf(Br(0), RefFunc(0), FcIndex(0x21, 0)),
                    ),
                    FcIndex(0x20, 0),
                    Drop(),
                ),
            )
            exports += Export("self", ExportDesc.Function(0))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(skippedByBranch) }

        val initializedInBothArms = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                listOf(nonNullFuncRef),
                listOf(
                    I32Const(1),
                    If(
                        BlockType.Empty,
                        listOf(RefFunc(0), FcIndex(0x21, 0)),
                        listOf(RefFunc(0), FcIndex(0x21, 0)),
                    ),
                    FcIndex(0x20, 0),
                    Drop(),
                ),
            )
            exports += Export("self", ExportDesc.Function(0))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(initializedInBothArms) }

        val initializedInsideBlock = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                listOf(nonNullFuncRef),
                listOf(
                    Block(
                        BlockType.Empty,
                        listOf(RefFunc(0), FcIndex(0x21, 0)),
                    ),
                    FcIndex(0x20, 0),
                    Drop(),
                ),
            )
            exports += Export("self", ExportDesc.Function(0))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(initializedInsideBlock) }

        val initializedInOneArm = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                listOf(nonNullFuncRef),
                listOf(
                    I32Const(1),
                    If(BlockType.Empty, listOf(RefFunc(0), FcIndex(0x21, 0)), emptyList()),
                    FcIndex(0x20, 0),
                    Drop(),
                ),
            )
            exports += Export("self", ExportDesc.Function(0))
        }
        assertFailsWith<InvalidModule> { ModuleValidator.validate(initializedInOneArm) }
    }

    @Test
    fun usesReferenceSubtypingForBrTableAndTailCalls() {
        val concreteRef = RefType(HeapType.Index(0), nullable = false)
        val branchModule = module {
            types += FuncType(emptyList(), listOf(FuncRefType))
            functions += Function(
                0,
                emptyList(),
                listOf(
                    Block(
                        BlockType.Single(FuncRefType),
                        listOf(
                            Block(
                                BlockType.Single(RefType(HeapType.Index(0), nullable = true)),
                                listOf(RefFunc(0), I32Const(0), BrTable(intArrayOf(0), 1)),
                            ),
                        ),
                    ),
                ),
            )
            exports += Export("branch", ExportDesc.Function(0))
        }
        ModuleValidator.validate(branchModule)

        val tailCallModule = module {
            types += FuncType(emptyList(), emptyList())
            types += FuncType(emptyList(), listOf(concreteRef))
            types += FuncType(emptyList(), listOf(FuncRefType))
            functions += Function(0, emptyList(), emptyList())
            functions += Function(1, emptyList(), listOf(RefFunc(0)))
            functions += Function(2, emptyList(), listOf(ReturnCall(1)))
            exports += Export("referenced", ExportDesc.Function(0))
        }
        ModuleValidator.validate(tailCallModule)
    }

    @Test
    fun functionLabelCarriesResultsForNestedBrTable() {
        val module = module {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    Block(
                        BlockType.Single(ValType.I32),
                        listOf(
                            I32Const(50),
                            I32Const(0),
                            BrTable(targets = intArrayOf(0, 1), defaultTarget = 0),
                            I32Const(51),
                        ),
                    ),
                ),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun rejectsDeclaredBlockResultMismatchAfterUnreachableBody() {
        val module = module {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    Block(
                        BlockType.Single(ValType.I64),
                        listOf(Unreachable, Unreachable, Unreachable, Select),
                    ),
                ),
            )
        }

        assertFailsWith<InvalidModule> { ModuleValidator.validate(module) }
    }

    @Test
    fun rejectsOuterStackValueBeforeNestedUnreachableBlock() {
        val module = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    Block(
                        BlockType.Empty,
                        listOf(I32Const(3), Block(BlockType.Empty, listOf(Unreachable))),
                    ),
                ),
            )
        }

        assertFailsWith<InvalidModule> { ModuleValidator.validate(module) }
    }

    @Test
    fun nestedUnreachableBlockKeepsItsDeclaredBoundaryType() {
        val module = module {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    Block(
                        BlockType.Single(ValType.I32),
                        listOf(Block(BlockType.Single(ValType.I32), listOf(Unreachable))),
                    ),
                ),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun nestedNonFallthroughStillSuppressesDeadLocalInitializationChecks() {
        val nonNullFuncRef = RefType(HeapType.Func, nullable = false)
        val module = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                typeIndex = 0,
                locals = listOf(nonNullFuncRef),
                body = listOf(
                    Block(BlockType.Empty, listOf(Return)),
                    FcIndex(0x20, 0),
                    Drop(),
                ),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun canonicalEquivalentDefinedTypesMatchWithinAModule() {
        val first = RefType(HeapType.Index(0), nullable = false)
        val second = RefType(HeapType.Index(1), nullable = false)
        val module = module {
            types += StructType(emptyList())
            types += StructType(emptyList())
            types += FuncType(listOf(first), listOf(second))
            functions += Function(
                typeIndex = 2,
                locals = emptyList(),
                body = listOf(FcIndex(0x20, 0)),
            )
        }

        ModuleValidator.validate(module)
    }

    @Test
    fun untypedSelectRejectsKnownReferenceOnPolymorphicStack() {
        val module = module {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                emptyList(),
                listOf(Unreachable, RefNull(HeapType.Func), I32Const(0), Select, Drop()),
            )
        }

        assertFailsWith<InvalidModule> { ModuleValidator.validate(module) }
    }

    @Test
    fun enforcesExactDecodedModuleAndBodySizes() {
        Module.decode(EMPTY_MODULE, ModuleValidationLimits(maxModuleSizeBytes = EMPTY_MODULE.size.toLong()))
        assertFailsWith<LimitExceeded> {
            Module.decode(EMPTY_MODULE, ModuleValidationLimits(maxModuleSizeBytes = EMPTY_MODULE.size - 1L))
        }

        Module.decode(SINGLE_EMPTY_FUNCTION, ModuleValidationLimits(maxFunctionBodySizeBytes = 2))
        assertFailsWith<LimitExceeded> {
            Module.decode(SINGLE_EMPTY_FUNCTION, ModuleValidationLimits(maxFunctionBodySizeBytes = 1))
        }
    }

    private fun module(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER)

    private fun legacyRethrowModule(rethrowDepth: Int): Module = module {
        types += FuncType(listOf(ValType.I32), emptyList())
        types += FuncType(emptyList(), listOf(ValType.I32))
        tags += Tag(typeIndex = 0)
        functions += Function(
            1,
            emptyList(),
            listOf(
                LegacyTry(
                    BlockType.Single(ValType.I32),
                    body = listOf(
                        LegacyTry(
                            BlockType.Single(ValType.I32),
                            body = listOf(I32Const(42), Throw(0)),
                            catches = listOf(
                                LegacyCatch(
                                    0,
                                    listOf(Block(BlockType.Empty, listOf(Rethrow(rethrowDepth)))),
                                ),
                            ),
                            catchAll = null,
                            delegateDepth = null,
                        ),
                    ),
                    catches = listOf(LegacyCatch(0, emptyList())),
                    catchAll = null,
                    delegateDepth = null,
                ),
            ),
        )
    }

    private companion object {
        val WASM_HEADER: ByteArray = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val EMPTY_MODULE: ByteArray = WASM_HEADER.copyOf()
        val SINGLE_EMPTY_FUNCTION: ByteArray = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
            0x03, 0x02, 0x01, 0x00,
            0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B,
        )
    }
}
