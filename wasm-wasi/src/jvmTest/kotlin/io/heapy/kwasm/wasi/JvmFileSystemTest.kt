package io.heapy.kwasm.wasi

import io.heapy.kwasm.Limits
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.MemoryType
import io.heapy.kwasm.Value
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class JvmFileSystemTest {
    @Test
    fun realDirectoryEnumerationReturnsSortedImmediateChildren() =
        withTempDirectory { temporary ->
            val rootPath = Files.createDirectory(temporary.resolve("root"))
            Files.createDirectory(rootPath.resolve("subdir"))
            Files.write(rootPath.resolve("z.txt"), byteArrayOf(1))
            Files.write(rootPath.resolve("a.txt"), byteArrayOf(2))

            val fileSystem = JvmFileSystem(rootPath)
            val entries = runBlocking {
                fileSystem.readEntries()
            }

            assertEquals(listOf("a.txt", "subdir", "z.txt"), entries.map { it.name })
            assertEquals(
                listOf(
                    WasiFileType.REGULAR_FILE,
                    WasiFileType.DIRECTORY,
                    WasiFileType.REGULAR_FILE,
                ),
                entries.map { it.fileType },
            )
            val aStat = runBlocking {
                fileSystem.stat("a.txt", followSymlinks = false)
            }
            assertEquals(aStat.attributes.inode, entries[0].inode)
            val nonZeroInodes = entries.map { it.inode }.filter { it != 0uL }
            assertEquals(nonZeroInodes.size, nonZeroInodes.distinct().size)
        }

    @Test
    fun realFilesSupportOpenReadWriteSeekCreateAndClose() = withTempDirectory { temporary ->
        val root = Files.createDirectory(temporary.resolve("root"))
        Files.write(root.resolve("message.txt"), "hello".encodeToByteArray())
        val memory = memory()
        val wasi = wasi(root, memory)
        val rights = WasiRights.FD_READ or WasiRights.FD_WRITE or WasiRights.FD_SEEK or
            WasiRights.FD_TELL or WasiRights.FD_FILESTAT_GET or
            WasiRights.FD_DATASYNC or WasiRights.FD_SYNC

        val opened = pathOpen(
            wasi = wasi,
            memory = memory,
            path = "message.txt",
            rights = rights,
        )
        assertEquals(WasiErrno.SUCCESS.code, opened.errno)
        val fd = opened.fd

        memory.iovec(address = 128, buffer = 256, length = 5)
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_read", i32(fd), i32(128), i32(1), i32(112)),
        )
        assertEquals(5u, memory.u32(112))
        assertContentEquals("hello".encodeToByteArray(), memory.load(256, 5))

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_seek", i32(fd), i64(0), i32(0), i32(120)),
        )
        assertEquals(0uL, memory.u64(120))
        memory.store(272, "H".encodeToByteArray())
        memory.iovec(address = 136, buffer = 272, length = 1)
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_write", i32(fd), i32(136), i32(1), i32(116)),
        )
        assertEquals(WasiErrno.SUCCESS.code, call(wasi, "fd_datasync", i32(fd)))
        assertEquals(WasiErrno.SUCCESS.code, call(wasi, "fd_sync", i32(fd)))
        assertEquals(WasiErrno.SUCCESS.code, call(wasi, "fd_close", i32(fd)))
        assertEquals(
            WasiErrno.BADF.code,
            call(wasi, "fd_read", i32(fd), i32(128), i32(1), i32(112)),
        )
        assertContentEquals(
            "Hello".encodeToByteArray(),
            Files.readAllBytes(root.resolve("message.txt")),
        )

        val created = pathOpen(
            wasi = wasi,
            memory = memory,
            path = "created.txt",
            rights = WasiRights.FD_WRITE or WasiRights.FD_SEEK,
            openFlags = WasiOpenFlags.CREATE or WasiOpenFlags.EXCLUSIVE,
        )
        assertEquals(WasiErrno.SUCCESS.code, created.errno)
        memory.store(288, "created".encodeToByteArray())
        memory.iovec(address = 144, buffer = 288, length = 7)
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_write", i32(created.fd), i32(144), i32(1), i32(124)),
        )
        assertEquals(WasiErrno.SUCCESS.code, call(wasi, "fd_close", i32(created.fd)))
        assertContentEquals(
            "created".encodeToByteArray(),
            Files.readAllBytes(root.resolve("created.txt")),
        )
    }

    @Test
    fun traversalAbsolutePathsAndSymlinkEscapesAreBlocked() = withTempDirectory { temporary ->
        val root = Files.createDirectory(temporary.resolve("root"))
        val outside = Files.createDirectory(temporary.resolve("outside"))
        val secret = Files.write(outside.resolve("secret.txt"), "secret".encodeToByteArray())
        Files.createSymbolicLink(root.resolve("escape.txt"), secret)
        Files.createSymbolicLink(root.resolve("escape-dir"), outside)
        val memory = memory()
        val wasi = wasi(root, memory)

        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            pathOpen(wasi, memory, "../outside/secret.txt", WasiRights.FD_READ).errno,
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            pathOpen(wasi, memory, secret.toString(), WasiRights.FD_READ).errno,
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            pathOpen(
                wasi = wasi,
                memory = memory,
                path = "escape.txt",
                rights = WasiRights.FD_READ,
                lookupFlags = 1,
            ).errno,
        )
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            pathOpen(
                wasi = wasi,
                memory = memory,
                path = "escape-dir/created.txt",
                rights = WasiRights.FD_WRITE,
                openFlags = WasiOpenFlags.CREATE,
            ).errno,
        )
        assertFalse(Files.exists(outside.resolve("created.txt")))
        assertContentEquals("secret".encodeToByteArray(), Files.readAllBytes(secret))
    }

    private fun wasi(
        root: Path,
        memory: MemoryInstance,
    ): WasiPreview1 =
        WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", JvmFileSystem(root))),
            ),
        ).also { it.attach(memory) }

    private fun pathOpen(
        wasi: WasiPreview1,
        memory: MemoryInstance,
        path: String,
        rights: ULong,
        openFlags: Int = 0,
        lookupFlags: Int = 0,
    ): OpenResult {
        val pathBytes = path.encodeToByteArray()
        memory.store(PATH_ADDRESS.toLong(), pathBytes)
        val errno = call(
            wasi,
            "path_open",
            i32(3),
            i32(lookupFlags),
            i32(PATH_ADDRESS),
            i32(pathBytes.size),
            i32(openFlags),
            i64(rights.toLong()),
            i64(0),
            i32(0),
            i32(FD_ADDRESS),
        )
        return OpenResult(errno, if (errno == WasiErrno.SUCCESS.code) memory.u32(FD_ADDRESS).toInt() else -1)
    }

    private fun call(
        wasi: WasiPreview1,
        name: String,
        vararg arguments: Value,
    ): Int = runBlocking {
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

    private data class OpenResult(
        val errno: Int,
        val fd: Int,
    )

    private companion object {
        const val PATH_ADDRESS = 512
        const val FD_ADDRESS = 1024
    }
}

private inline fun withTempDirectory(block: (Path) -> Unit) {
    val temporary = Files.createTempDirectory("kwasm-wasi-jvm-")
    try {
        block(temporary)
    } finally {
        temporary.toFile().deleteRecursively()
    }
}
