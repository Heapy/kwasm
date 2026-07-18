package io.heapy.kwasm.wasi

import io.heapy.kwasm.Limits
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.MemoryType
import io.heapy.kwasm.Value
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WasiDescriptorOperationsTest {
    @Test
    fun descriptorMetadataPositionedIoMutationAndRenumberUsePreview1Layouts() {
        val fileSystem = InMemoryFileSystem()
        fileSystem.writeFile("file.txt", "hello".encodeToByteArray())
        fileSystem.writeFile("target.txt", "discard".encodeToByteArray())
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/", fileSystem.root)),
                clock = FixedWasiClock(realtimeNanos = 999u),
            ),
        )
        wasi.attach(memory)

        val rights =
            WasiRights.FD_READ or WasiRights.FD_WRITE or WasiRights.FD_SEEK or
                WasiRights.FD_TELL or WasiRights.FD_ADVISE or WasiRights.FD_SYNC or
                WasiRights.FD_DATASYNC or
                WasiRights.FD_ALLOCATE or WasiRights.FD_FDSTAT_SET_FLAGS or
                WasiRights.FD_FILESTAT_GET or WasiRights.FD_FILESTAT_SET_SIZE or
                WasiRights.FD_FILESTAT_SET_TIMES
        val fd = openFile(wasi, memory, "file.txt", rights)

        assertEquals(0, call(wasi, "fd_filestat_get", i32(fd), i32(512)))
        assertEquals(WasiFileType.REGULAR_FILE.code, memory.loadByte(528))
        assertEquals(1uL, memory.u64(536))
        assertEquals(5uL, memory.u64(544))
        assertEquals(0, call(wasi, "fd_tell", i32(fd), i32(600)))
        assertEquals(0uL, memory.u64(600))

        memory.store(700, "XY".encodeToByteArray())
        memory.iovec(address = 640, buffer = 700, length = 2)
        assertEquals(
            0,
            call(wasi, "fd_pwrite", i32(fd), i32(640), i32(1), i64(1), i32(608)),
        )
        assertEquals(2u, memory.u32(608))
        assertEquals(0, call(wasi, "fd_tell", i32(fd), i32(600)))
        assertEquals(0uL, memory.u64(600))

        memory.iovec(address = 648, buffer = 720, length = 2)
        memory.iovec(address = 656, buffer = 722, length = 3)
        assertEquals(
            0,
            call(wasi, "fd_pread", i32(fd), i32(648), i32(2), i64(0), i32(612)),
        )
        assertEquals(5u, memory.u32(612))
        assertContentEquals("hXYlo".encodeToByteArray(), memory.load(720, 5))
        assertEquals(0, call(wasi, "fd_tell", i32(fd), i32(600)))
        assertEquals(0uL, memory.u64(600))

        assertEquals(
            0,
            call(wasi, "fd_fdstat_set_flags", i32(fd), i32(WasiFdFlags.APPEND)),
        )
        memory.store(740, "!".encodeToByteArray())
        memory.iovec(address = 664, buffer = 740, length = 1)
        assertEquals(0, call(wasi, "fd_write", i32(fd), i32(664), i32(1), i32(616)))
        assertContentEquals("hXYlo!".encodeToByteArray(), fileSystem.readFile("file.txt"))
        assertEquals(0, call(wasi, "fd_tell", i32(fd), i32(600)))
        assertEquals(6uL, memory.u64(600))

        assertEquals(0, call(wasi, "fd_filestat_set_size", i32(fd), i64(4)))
        assertContentEquals("hXYl".encodeToByteArray(), fileSystem.readFile("file.txt"))
        assertEquals(0, call(wasi, "fd_allocate", i32(fd), i64(10), i64(2)))
        assertEquals(12, fileSystem.readFile("file.txt").size)
        assertEquals(0, call(wasi, "fd_advise", i32(fd), i64(2), i64(4), i32(0)))
        assertEquals(0, call(wasi, "fd_datasync", i32(fd)))
        assertEquals(0, call(wasi, "fd_sync", i32(fd)))
        assertEquals(
            WasiErrno.INVAL.code,
            call(wasi, "fd_advise", i32(fd), i64(0), i64(0), i32(6)),
        )

        assertEquals(
            0,
            call(wasi, "fd_filestat_set_times", i32(fd), i64(111), i64(222), i32(5)),
        )
        assertEquals(0, call(wasi, "fd_filestat_get", i32(fd), i32(512)))
        assertEquals(111uL, memory.u64(552))
        assertEquals(222uL, memory.u64(560))
        assertEquals(
            0,
            call(wasi, "fd_filestat_set_times", i32(fd), i64(0), i64(0), i32(10)),
        )
        assertEquals(0, call(wasi, "fd_filestat_get", i32(fd), i32(512)))
        assertEquals(999uL, memory.u64(552))
        assertEquals(999uL, memory.u64(560))
        assertEquals(
            WasiErrno.INVAL.code,
            call(wasi, "fd_filestat_set_times", i32(fd), i64(1), i64(2), i32(3)),
        )

        assertEquals(
            0,
            call(wasi, "fd_filestat_set_times", i32(3), i64(333), i64(444), i32(5)),
        )
        assertEquals(0, call(wasi, "fd_filestat_get", i32(3), i32(576)))
        assertEquals(WasiFileType.DIRECTORY.code, memory.loadByte(592))
        assertEquals(333uL, memory.u64(616))
        assertEquals(444uL, memory.u64(624))

        val targetFd = openFile(wasi, memory, "target.txt", rights)
        assertEquals(
            WasiErrno.BADF.code,
            call(wasi, "fd_renumber", i32(fd), i32(1234)),
        )
        assertEquals(0, call(wasi, "fd_renumber", i32(fd), i32(targetFd)))
        assertEquals(
            WasiErrno.BADF.code,
            call(wasi, "fd_filestat_get", i32(fd), i32(512)),
        )
        assertEquals(0, call(wasi, "fd_filestat_get", i32(targetFd), i32(512)))
        assertEquals(12uL, memory.u64(544))

        val reducedRights = rights and WasiRights.FD_WRITE.inv()
        assertEquals(
            0,
            call(
                wasi,
                "fd_fdstat_set_rights",
                i32(targetFd),
                i64(reducedRights.toLong()),
                i64(0),
            ),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_write", i32(targetFd), i32(664), i32(1), i32(616)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                wasi,
                "fd_fdstat_set_rights",
                i32(targetFd),
                i64(rights.toLong()),
                i64(0),
            ),
        )
    }

    @Test
    fun descriptorOperationsValidateRightsKindsFlagsAndUnsignedRanges() {
        val fileSystem = InMemoryFileSystem()
        fileSystem.writeFile("file.txt", byteArrayOf(1, 2, 3))
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(preopens = listOf(WasiPreopen("/", fileSystem.root))),
        )
        wasi.attach(memory)
        val readOnly = openFile(
            wasi,
            memory,
            "file.txt",
            WasiRights.FD_READ or WasiRights.FD_FILESTAT_GET,
        )

        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_tell", i32(readOnly), i32(0)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_allocate", i32(readOnly), i64(0), i64(1)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_datasync", i32(readOnly)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_sync", i32(readOnly)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_fdstat_set_flags", i32(readOnly), i32(WasiFdFlags.APPEND)),
        )
        assertEquals(
            WasiErrno.INVAL.code,
            call(wasi, "fd_fdstat_set_flags", i32(readOnly), i32(32)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_pread", i32(readOnly), i32(64), i32(0), i64(0), i32(32)),
        )

        val positioned = openFile(
            wasi,
            memory,
            "file.txt",
            WasiRights.FD_READ or WasiRights.FD_SEEK,
        )
        assertEquals(
            WasiErrno.OVERFLOW.code,
            call(wasi, "fd_pread", i32(positioned), i32(64), i32(0), i64(-1), i32(32)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_filestat_get", i32(positioned), i32(128)),
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(wasi, "fd_allocate", i32(3), i64(0), i64(1)),
        )
        assertEquals(
            WasiErrno.ISDIR.code,
            call(wasi, "fd_sync", i32(3)),
        )
        assertEquals(
            WasiErrno.BADF.code,
            call(wasi, "fd_sync", i32(999)),
        )
    }

    private fun openFile(
        wasi: WasiPreview1,
        memory: MemoryInstance,
        path: String,
        rights: ULong,
    ): Int {
        val bytes = path.encodeToByteArray()
        memory.store(1024, bytes)
        assertEquals(
            0,
            call(
                wasi,
                "path_open",
                i32(3),
                i32(0),
                i32(1024),
                i32(bytes.size),
                i32(0),
                i64(rights.toLong()),
                i64(0),
                i32(0),
                i32(1100),
            ),
        )
        return memory.u32(1100).toInt()
    }

    private fun call(
        wasi: WasiPreview1,
        name: String,
        vararg arguments: Value,
    ): Int = runImmediateDescriptorTest {
        val results = wasi.hostImport(name).fn.call(arguments.toList())
        if (results.isEmpty()) 0 else (results.single() as Value.I32).v
    }

    private fun memory(): MemoryInstance =
        MemoryInstance(MemoryType(Limits(min = 1u, max = 1u)))

    private fun i32(value: Int): Value = Value.I32(value)
    private fun i64(value: Long): Value = Value.I64(value)

    private fun MemoryInstance.u32(address: Int): UInt {
        val bytes = load(address.toLong(), 4)
        return bytes[0].toUByte().toUInt() or
            (bytes[1].toUByte().toUInt() shl 8) or
            (bytes[2].toUByte().toUInt() shl 16) or
            (bytes[3].toUByte().toUInt() shl 24)
    }

    private fun MemoryInstance.u64(address: Int): ULong {
        val bytes = load(address.toLong(), 8)
        var value = 0uL
        bytes.forEachIndexed { index, byte ->
            value = value or (byte.toUByte().toULong() shl (index * 8))
        }
        return value
    }

    private fun MemoryInstance.iovec(
        address: Int,
        buffer: Int,
        length: Int,
    ) {
        store(
            address.toLong(),
            byteArrayOf(
                buffer.toByte(),
                (buffer ushr 8).toByte(),
                (buffer ushr 16).toByte(),
                (buffer ushr 24).toByte(),
                length.toByte(),
                (length ushr 8).toByte(),
                (length ushr 16).toByte(),
                (length ushr 24).toByte(),
            ),
        )
    }
}

private fun <T> runImmediateDescriptorTest(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "operation suspended unexpectedly" }.getOrThrow()
}
