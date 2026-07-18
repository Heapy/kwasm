package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TableReferenceTypeTest {
    @Test
    fun abstractTableTypesEnforceTheReferenceHierarchy() {
        val owner = Instance(gcOwnerModule())
        val struct = Value.Ref.Gc(StructObject(owner, 0, mutableListOf()))
        val array = Value.Ref.Gc(ArrayObject(owner, 1, mutableListOf()))
        val exception = Value.Ref.Exn(
            GuestException(TagInstance(FuncType(emptyList(), emptyList())), emptyList()),
        )
        val externalizedAny = Value.Ref.AnyExtern(Value.Ref.Extern(7))

        val eqTable = table(EqRefType)
        eqTable.set(0, Value.Ref.I31(17))
        eqTable.set(0, struct)
        eqTable.set(0, array)
        assertWrongType { eqTable.set(0, externalizedAny) }

        val anyTable = table(AnyRefType)
        anyTable.set(0, Value.Ref.I31(17))
        anyTable.set(0, struct)
        anyTable.set(0, array)
        anyTable.set(0, externalizedAny)
        assertWrongType { anyTable.set(0, Value.Ref.Func(0)) }
        assertWrongType { anyTable.set(0, Value.Ref.Extern(1)) }

        val structTable = table(StructRefType)
        structTable.set(0, struct)
        assertWrongType { structTable.set(0, array) }
        assertWrongType { structTable.set(0, Value.Ref.I31(1)) }

        val arrayTable = table(ArrayRefType)
        arrayTable.set(0, array)
        assertWrongType { arrayTable.set(0, struct) }

        val externTable = table(ExternRefType)
        externTable.set(0, Value.Ref.Extern(4))
        externTable.set(0, Value.Ref.Host("host object"))
        assertWrongType { externTable.set(0, struct) }

        val exnTable = table(ExnRefType)
        exnTable.set(0, exception)
        assertWrongType { exnTable.set(0, Value.Ref.Extern(4)) }
    }

    @Test
    fun nullReferencesRetainTheirHeapHierarchy() {
        table(FuncRefType).apply {
            set(0, Value.NULL_FUNC)
            assertWrongType { set(0, Value.NULL_EXTERN) }
            assertWrongType { set(0, Value.NULL_GC) }
        }
        table(ExternRefType).apply {
            set(0, Value.NULL_EXTERN)
            set(0, Value.Ref.Host(null))
            assertWrongType { set(0, Value.NULL_FUNC) }
        }
        table(AnyRefType).apply {
            set(0, Value.NULL_GC)
            assertWrongType { set(0, Value.NULL_FUNC) }
            assertWrongType { set(0, Value.NULL_EXTERN) }
        }
        table(ExnRefType).apply {
            set(0, Value.NULL_EXN)
            assertWrongType { set(0, Value.NULL_GC) }
        }
        table(RefType(HeapType.Any, nullable = false)).apply {
            assertWrongType { set(0, Value.NULL_GC) }
            set(0, Value.Ref.I31(0))
        }
        table(RefType(HeapType.NoFunc, nullable = true)).apply {
            set(0, Value.NULL_FUNC)
            assertWrongType { set(0, Value.Ref.Func(0)) }
        }
        table(RefType(HeapType.NoExtern, nullable = true)).apply {
            set(0, Value.NULL_EXTERN)
            assertWrongType { set(0, Value.Ref.Extern(0)) }
        }
        table(RefType(HeapType.None, nullable = true)).apply {
            set(0, Value.NULL_GC)
            assertWrongType { set(0, Value.Ref.I31(0)) }
        }
        table(RefType(HeapType.NoExn, nullable = true)).apply {
            set(0, Value.NULL_EXN)
            assertWrongType {
                set(
                    0,
                    Value.Ref.Exn(
                        GuestException(
                            TagInstance(FuncType(emptyList(), emptyList())),
                            emptyList(),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun concreteGcTablesUseDeclaredSubtypingAndCanonicalTypesAcrossModules() {
        val module = gcOwnerModule()
        val owner = Instance(module)
        val foreignOwner = Instance(gcOwnerModule())
        val baseTable = TableInstance(
            TableType(RefType(HeapType.Index(0), nullable = true), Limits(1u, 2u)),
            module,
        )

        val derived = Value.Ref.Gc(StructObject(owner, 2, mutableListOf()))
        baseTable.set(0, derived)
        assertEquals(derived, baseTable.get(0))
        baseTable.set(0, Value.NULL_GC)

        assertWrongType {
            baseTable.set(0, Value.Ref.Gc(ArrayObject(owner, 1, mutableListOf())))
        }
        val equivalentForeign =
            Value.Ref.Gc(StructObject(foreignOwner, 2, mutableListOf()))
        baseTable.set(0, equivalentForeign)
        assertEquals(equivalentForeign, baseTable.get(0))

        val nonNullable = TableInstance(
            TableType(RefType(HeapType.Index(0), nullable = false), Limits(1u, 1u)),
            module,
        )
        assertWrongType { nonNullable.set(0, Value.NULL_GC) }
        nonNullable.set(0, derived)
    }

    @Test
    fun concreteFunctionTablesAcceptCanonicalTypesAcrossModules() {
        val module = functionOwnerModule()
        val owner = Instance(module)
        val foreignOwner = Instance(functionOwnerModule())
        val concreteType = RefType(HeapType.Index(0), nullable = true)
        val table = TableInstance(TableType(concreteType, Limits(1u, 2u)), module)

        table.set(0, Value.Ref.Func(0, owner))
        table.set(0, Value.NULL_FUNC)
        table.set(0, Value.Ref.Func(0, foreignOwner))
        assertWrongType { table.set(0, Value.Ref.Func(0)) }
        assertWrongType { table.set(0, Value.NULL_GC) }

        assertFailsWith<IllegalArgumentException> {
            TableInstance(TableType(concreteType, Limits(0u, 1u)))
        }
    }

    @Test
    fun growAndSnapshotRestoreCannotBypassElementTyping() {
        val table = table(StructRefType, maximum = 3u)
        val owner = Instance(gcOwnerModule())
        val struct = Value.Ref.Gc(StructObject(owner, 0, mutableListOf()))

        assertWrongType { table.grow(1, Value.Ref.I31(2)) }
        assertEquals(1, table.size)
        assertEquals(1, table.grow(1, struct))
        assertEquals(2, table.size)
        assertIs<Value.Ref.Gc>(table.get(1))

        assertFailsWith<SnapshotStateException> {
            table.validateSnapshotRefs(listOf(struct, Value.Ref.I31(2)))
        }
        table.restoreSnapshotRefs(listOf(struct))
        assertEquals(struct, table.get(0))

        val defensiveView = table.refs
        defensiveView[0] = Value.Ref.I31(9)
        assertEquals(struct, table.get(0))
    }

    private fun table(type: RefType, maximum: UInt = 1u): TableInstance =
        TableInstance(TableType(type, Limits(1u, maximum)))

    private fun assertWrongType(block: () -> Unit) {
        assertFailsWith<IllegalArgumentException>(block = block)
    }

    private fun gcOwnerModule(): Module = validatedModule {
        types += StructType(emptyList(), isFinal = false)
        types += ArrayType(FieldType(StorageType.Value(ValType.I32), Mutability.VAR))
        types += StructType(emptyList(), supertypes = listOf(0))
    }

    private fun functionOwnerModule(): Module = validatedModule {
        types += FuncType(emptyList(), emptyList())
        functions += Function(0, emptyList(), emptyList())
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(ByteArray(0)).also(ModuleValidator::validate)
}
