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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasiPathOperationsTest {
    @Test
    fun preview1PathFamilyMutatesAndReportsInMemoryFilesystem() {
        val fileSystem = InMemoryFileSystem()
        fileSystem.writeFile("source.txt", "payload".encodeToByteArray())
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", fileSystem.root)),
                clock = FixedWasiClock(realtimeNanos = 999u),
            ),
        )
        wasi.attach(memory)

        val directory = memory.path(0, "dir")
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_create_directory",
                i32(PREOPEN_FD),
                i32(directory.address),
                i32(directory.length),
            ),
        )
        assertTrue(fileSystem.exists("dir"))

        val target = memory.path(64, "../source.txt")
        val link = memory.path(128, "dir/link")
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_symlink",
                i32(target.address),
                i32(target.length),
                i32(PREOPEN_FD),
                i32(link.address),
                i32(link.length),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_readlink",
                i32(PREOPEN_FD),
                i32(link.address),
                i32(link.length),
                i32(256),
                i32(64),
                i32(240),
            ),
        )
        assertEquals(target.length.toUInt(), memory.u32(240))
        assertContentEquals(
            "../source.txt".encodeToByteArray(),
            memory.load(256, target.length),
        )

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_filestat_get",
                i32(PREOPEN_FD),
                i32(0),
                i32(link.address),
                i32(link.length),
                i32(FILESTAT_ADDRESS),
            ),
        )
        assertEquals(
            WasiFileType.SYMBOLIC_LINK.code,
            memory.loadByte(
                (FILESTAT_ADDRESS + FILESTAT_FILE_TYPE_OFFSET).toLong(),
            ),
        )
        val symbolicLinkInode = memory.u64(FILESTAT_ADDRESS + FILESTAT_INODE_OFFSET)
        assertTrue(symbolicLinkInode > 0uL)
        assertEquals(target.length.toULong(), memory.u64(FILESTAT_ADDRESS + FILESTAT_SIZE_OFFSET))
        assertTrue(memory.u64(FILESTAT_ADDRESS + FILESTAT_ACCESS_TIME_OFFSET) > 0uL)
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_filestat_get",
                i32(PREOPEN_FD),
                i32(1),
                i32(link.address),
                i32(link.length),
                i32(FILESTAT_ADDRESS),
            ),
        )
        assertEquals(
            WasiFileType.REGULAR_FILE.code,
            memory.loadByte(
                (FILESTAT_ADDRESS + FILESTAT_FILE_TYPE_OFFSET).toLong(),
            ),
        )
        val followedSourceInode = memory.u64(FILESTAT_ADDRESS + FILESTAT_INODE_OFFSET)

        val source = memory.path(320, "source.txt")
        val timesFlags =
            WasiFileStatSetTimesFlags.ACCESS_TIME or
                WasiFileStatSetTimesFlags.MODIFICATION_TIME
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_filestat_set_times",
                i32(PREOPEN_FD),
                i32(0),
                i32(source.address),
                i32(source.length),
                i64(111),
                i64(222),
                i32(timesFlags),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_filestat_get",
                i32(PREOPEN_FD),
                i32(0),
                i32(source.address),
                i32(source.length),
                i32(FILESTAT_ADDRESS),
            ),
        )
        assertEquals(111uL, memory.u64(FILESTAT_ADDRESS + FILESTAT_ACCESS_TIME_OFFSET))
        assertEquals(222uL, memory.u64(FILESTAT_ADDRESS + FILESTAT_MODIFICATION_TIME_OFFSET))
        val sourceInode = memory.u64(FILESTAT_ADDRESS + FILESTAT_INODE_OFFSET)
        assertTrue(sourceInode > 0uL)
        assertTrue(sourceInode != symbolicLinkInode)
        assertEquals(followedSourceInode, sourceInode)

        val hardLink = memory.path(384, "dir/hard")
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_link",
                i32(PREOPEN_FD),
                i32(0),
                i32(source.address),
                i32(source.length),
                i32(PREOPEN_FD),
                i32(hardLink.address),
                i32(hardLink.length),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_filestat_get",
                i32(PREOPEN_FD),
                i32(0),
                i32(source.address),
                i32(source.length),
                i32(FILESTAT_ADDRESS),
            ),
        )
        assertEquals(2uL, memory.u64(FILESTAT_ADDRESS + FILESTAT_LINK_COUNT_OFFSET))
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_filestat_get",
                i32(PREOPEN_FD),
                i32(0),
                i32(hardLink.address),
                i32(hardLink.length),
                i32(FILESTAT_ADDRESS),
            ),
        )
        assertEquals(sourceInode, memory.u64(FILESTAT_ADDRESS + FILESTAT_INODE_OFFSET))

        val renamed = memory.path(448, "dir/renamed")
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_rename",
                i32(PREOPEN_FD),
                i32(hardLink.address),
                i32(hardLink.length),
                i32(PREOPEN_FD),
                i32(renamed.address),
                i32(renamed.length),
            ),
        )
        assertFalse(fileSystem.exists("dir/hard"))
        assertTrue(fileSystem.exists("dir/renamed"))

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_unlink_file",
                i32(PREOPEN_FD),
                i32(renamed.address),
                i32(renamed.length),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_unlink_file",
                i32(PREOPEN_FD),
                i32(link.address),
                i32(link.length),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_remove_directory",
                i32(PREOPEN_FD),
                i32(directory.address),
                i32(directory.length),
            ),
        )
        assertFalse(fileSystem.exists("dir"))
        assertContentEquals(
            "payload".encodeToByteArray(),
            fileSystem.readFile("source.txt"),
        )
    }

    @Test
    fun pathRightsFlagsAndTraversalFailBeforeMutation() {
        val fileSystem = InMemoryFileSystem()
        fileSystem.writeFile("keep.txt", byteArrayOf(1))
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(
                    WasiPreopen(
                        guestPath = "/data",
                        directory = fileSystem.root,
                        rightsBase = WasiRights.PATH_FILESTAT_GET,
                    ),
                ),
            ),
        )
        wasi.attach(memory)
        val keep = memory.path(0, "keep.txt")

        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                wasi,
                "path_unlink_file",
                i32(PREOPEN_FD),
                i32(keep.address),
                i32(keep.length),
            ),
        )
        assertTrue(fileSystem.exists("keep.txt"))

        val escape = memory.path(64, "../escape")
        val fullRightsWasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", fileSystem.root)),
            ),
        ).also { it.attach(memory) }
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                fullRightsWasi,
                "path_create_directory",
                i32(PREOPEN_FD),
                i32(escape.address),
                i32(escape.length),
            ),
        )
        assertFalse(fileSystem.exists("escape"))

        fileSystem.createDirectories("nested")
        val normalized = memory.path(128, "nested/../inside")
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                fullRightsWasi,
                "path_create_directory",
                i32(PREOPEN_FD),
                i32(normalized.address),
                i32(normalized.length),
            ),
        )
        assertTrue(fileSystem.exists("inside"))
        val nestedEscape = memory.path(192, "nested/../../escape")
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                fullRightsWasi,
                "path_create_directory",
                i32(PREOPEN_FD),
                i32(nestedEscape.address),
                i32(nestedEscape.length),
            ),
        )

        val trailingHardLink = memory.path(256, "hard/")
        assertEquals(
            WasiErrno.NOENT.code,
            call(
                fullRightsWasi,
                "path_link",
                i32(PREOPEN_FD),
                i32(0),
                i32(keep.address),
                i32(keep.length),
                i32(PREOPEN_FD),
                i32(trailingHardLink.address),
                i32(trailingHardLink.length),
            ),
        )
        assertFalse(fileSystem.exists("hard"))

        val relativeTarget = memory.path(320, "keep.txt")
        val trailingSymbolicLink = memory.path(384, "link/")
        assertEquals(
            WasiErrno.NOENT.code,
            call(
                fullRightsWasi,
                "path_symlink",
                i32(relativeTarget.address),
                i32(relativeTarget.length),
                i32(PREOPEN_FD),
                i32(trailingSymbolicLink.address),
                i32(trailingSymbolicLink.length),
            ),
        )
        assertFalse(fileSystem.exists("link"))

        val absoluteTarget = memory.path(448, "/outside")
        val absoluteLink = memory.path(512, "absolute-link")
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                fullRightsWasi,
                "path_symlink",
                i32(absoluteTarget.address),
                i32(absoluteTarget.length),
                i32(PREOPEN_FD),
                i32(absoluteLink.address),
                i32(absoluteLink.length),
            ),
        )
        assertFalse(fileSystem.exists("absolute-link"))

        val trailingUnlink = memory.path(576, "keep.txt/")
        assertEquals(
            WasiErrno.NOTDIR.code,
            call(
                fullRightsWasi,
                "path_unlink_file",
                i32(PREOPEN_FD),
                i32(trailingUnlink.address),
                i32(trailingUnlink.length),
            ),
        )
        assertTrue(fileSystem.exists("keep.txt"))

        val conflicting =
            WasiFileStatSetTimesFlags.ACCESS_TIME or
                WasiFileStatSetTimesFlags.ACCESS_TIME_NOW
        assertEquals(
            WasiErrno.INVAL.code,
            call(
                fullRightsWasi,
                "path_filestat_set_times",
                i32(PREOPEN_FD),
                i32(0),
                i32(keep.address),
                i32(keep.length),
                i64(123),
                i64(0),
                i32(conflicting),
            ),
        )
    }

    private fun call(
        wasi: WasiPreview1,
        name: String,
        vararg arguments: Value,
    ): Int = runPathImmediate {
        val results = wasi.hostImport(name).fn.call(arguments.toList())
        (results.single() as Value.I32).v
    }

    private fun memory(): MemoryInstance =
        MemoryInstance(MemoryType(Limits(min = 1u, max = 1u)))

    private fun i32(value: Int): Value = Value.I32(value)
    private fun i64(value: Long): Value = Value.I64(value)

    private fun MemoryInstance.path(
        address: Int,
        path: String,
    ): GuestPath =
        path.encodeToByteArray().let { bytes ->
            store(address.toLong(), bytes)
            GuestPath(address, bytes.size)
        }

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

    private data class GuestPath(
        val address: Int,
        val length: Int,
    )

    private companion object {
        const val PREOPEN_FD = 3
        const val FILESTAT_ADDRESS = 1024
        const val FILESTAT_FILE_TYPE_OFFSET = 16
        const val FILESTAT_INODE_OFFSET = 8
        const val FILESTAT_LINK_COUNT_OFFSET = 24
        const val FILESTAT_SIZE_OFFSET = 32
        const val FILESTAT_ACCESS_TIME_OFFSET = 40
        const val FILESTAT_MODIFICATION_TIME_OFFSET = 48
    }
}

private fun <T> runPathImmediate(block: suspend () -> T): T {
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
