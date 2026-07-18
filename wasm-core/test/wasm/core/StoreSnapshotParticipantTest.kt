package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class StoreSnapshotParticipantTest {
    @Test
    fun hostStateCannotBeCapturedOutsideTheRuntimeSnapshotGate() {
        val store = Store()
        store.registerHostSnapshotParticipant(participant("test.capture"))

        val failure = assertFailsWith<SnapshotStateException> {
            store.captureHostSnapshotState(hooks = null)
        }

        assertFalse(failure.message.orEmpty().contains("test.capture"))
        assertEquals(0, captures)
    }

    @Test
    fun preparedRestoreIsSingleUse() {
        val store = Store()
        store.registerHostSnapshotParticipant(participant("test.single-use"))
        val restore = store.prepareHostSnapshotRestore(
            listOf(RuntimeHostSnapshot("test.single-use", byteArrayOf(1))),
            hooks = null,
        )

        restore.commit()

        assertEquals(1, commits)
        assertFailsWith<SnapshotStateException> { restore.commit() }
        assertEquals(1, commits)
    }

    @Test
    fun preparedRestoreBecomesStaleWhenStoreStateChanges() {
        val store = Store()
        store.registerHostSnapshotParticipant(participant("test.stale"))
        val restore = store.prepareHostSnapshotRestore(
            listOf(RuntimeHostSnapshot("test.stale", byteArrayOf(1))),
            hooks = null,
        )

        store.addFuel(1)

        assertFailsWith<SnapshotStateException> { restore.commit() }
        assertEquals(0, commits)
    }

    @Test
    fun equalParticipantIdsAreIsolatedByInstance() {
        val store = Store()
        val module = Module.decode(
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00),
        )
        val first = Instance(store, module, ResolvedImports())
        val second = Instance(store, module, ResolvedImports())
        val firstParticipant = scopedParticipant("test.scoped")
        val secondParticipant = scopedParticipant("test.scoped")

        store.registerHostSnapshotParticipant(first, firstParticipant)
        store.registerHostSnapshotParticipant(second, secondParticipant)

        assertSame(
            firstParticipant,
            store.hostSnapshotParticipant("test.scoped", first),
        )
        assertSame(
            secondParticipant,
            store.hostSnapshotParticipant("test.scoped", second),
        )
        assertNull(store.hostSnapshotParticipant("test.scoped"))
    }

    private var captures: Int = 0
    private var commits: Int = 0

    private fun participant(id: String): HostSnapshotParticipant =
        object : HostSnapshotParticipant {
            override val id: String = id

            override fun capture(hooks: HostSnapshotHooks?): ByteArray {
                captures++
                return byteArrayOf(1)
            }

            override fun prepareRestore(
                payload: ByteArray,
                hooks: HostSnapshotHooks?,
            ): HostSnapshotRestore {
                assertEquals(listOf(1.toByte()), payload.toList())
                return HostSnapshotRestore { commits++ }
            }
        }

    private fun scopedParticipant(id: String): InstanceScopedHostSnapshotParticipant =
        object : InstanceScopedHostSnapshotParticipant {
            override val id: String = id

            override fun capture(
                instance: Instance,
                hooks: HostSnapshotHooks?,
            ): ByteArray = ByteArray(0)

            override fun prepareRestore(
                payload: ByteArray,
                instance: Instance,
                hooks: HostSnapshotHooks?,
            ): HostSnapshotRestore = HostSnapshotRestore {}
        }
}
