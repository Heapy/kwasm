package io.heapy.kwasm.wasi

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmPathOperationsTest {
    @Test
    fun nioBackendSupportsConfinedPreview1PathFamily() = withPathTempDirectory { temporary ->
        val rootPath = Files.createDirectory(temporary.resolve("root"))
        val outsidePath = Files.createDirectory(temporary.resolve("outside"))
        Files.write(rootPath.resolve("source.txt"), "source".encodeToByteArray())
        Files.write(outsidePath.resolve("secret.txt"), "secret".encodeToByteArray())
        val root = JvmFileSystem(rootPath)

        runBlocking {
            val self = root.open(
                ".",
                WasiPathOpenOptions(requireDirectory = true),
            ) as WasiOpenedDirectory
            assertEquals(root.statSelf().inode, self.directory.statSelf().inode)
            assertJvmErrno(WasiErrno.NOENT) {
                root.open("", WasiPathOpenOptions(requireDirectory = true))
            }

            root.createDirectory("dir")
            root.createSymbolicLink("../source.txt", "dir/link")
            assertContentEquals(
                "../source.txt".encodeToByteArray(),
                root.readLink("dir/link"),
            )
            assertEquals(
                WasiFileType.SYMBOLIC_LINK,
                root.stat("dir/link", followSymlinks = false).fileType,
            )
            assertEquals(
                WasiFileType.REGULAR_FILE,
                root.stat("dir/link", followSymlinks = true).fileType,
            )

            root.setTimes(
                path = "source.txt",
                followSymlinks = false,
                accessTimeNanos = 1_000_000_000uL,
                modificationTimeNanos = 2_000_000_000uL,
            )
            val stat = root.stat("source.txt", followSymlinks = false)
            assertTrue(stat.attributes.accessTimeNanos > 0uL)
            assertTrue(stat.attributes.modificationTimeNanos > 0uL)

            root.link(
                sourcePath = "source.txt",
                followSourceSymlink = false,
                targetDirectory = root,
                targetPath = "dir/hard",
            )
            assertTrue(Files.isSameFile(rootPath.resolve("source.txt"), rootPath.resolve("dir/hard")))
            root.rename("dir/hard", root, "dir/renamed")
            assertFalse(Files.exists(rootPath.resolve("dir/hard")))
            assertTrue(Files.exists(rootPath.resolve("dir/renamed")))
            root.unlinkFile("dir/renamed")
            root.unlinkFile("dir/link")
            root.removeDirectory("dir")
            assertFalse(Files.exists(rootPath.resolve("dir")))

            root.createDirectory("nested")
            root.createDirectory("nested/../inside")
            assertTrue(Files.isDirectory(rootPath.resolve("inside")))
            assertJvmErrno(WasiErrno.NOTCAPABLE) {
                root.createDirectory("nested/../../escape")
            }
            assertFalse(Files.exists(temporary.resolve("escape")))

            assertJvmErrno(WasiErrno.NOENT) {
                root.link(
                    sourcePath = "source.txt",
                    followSourceSymlink = false,
                    targetDirectory = root,
                    targetPath = "bad-hard-link/",
                )
            }
            assertFalse(Files.exists(rootPath.resolve("bad-hard-link")))
            assertJvmErrno(WasiErrno.NOENT) {
                root.createSymbolicLink("source.txt", "bad-symbolic-link/")
            }
            assertFalse(Files.exists(rootPath.resolve("bad-symbolic-link")))
            assertJvmErrno(WasiErrno.NOTCAPABLE) {
                root.createSymbolicLink("/outside", "absolute-link")
            }
            assertFalse(Files.exists(rootPath.resolve("absolute-link")))
            assertJvmErrno(WasiErrno.NOTDIR) {
                root.unlinkFile("source.txt/")
            }
            assertTrue(Files.exists(rootPath.resolve("source.txt")))
            root.removeDirectory("inside")
            root.removeDirectory("nested")

            Files.createSymbolicLink(
                rootPath.resolve("escape"),
                outsidePath.resolve("secret.txt"),
            )
            assertJvmErrno(WasiErrno.NOTCAPABLE) {
                root.stat("escape", followSymlinks = true)
            }
            assertContentEquals(
                "secret".encodeToByteArray(),
                Files.readAllBytes(outsidePath.resolve("secret.txt")),
            )
        }
    }
}

private suspend fun assertJvmErrno(
    expected: WasiErrno,
    block: suspend () -> Unit,
) {
    val failure = assertFailsWith<WasiFileSystemException> { block() }
    assertEquals(expected, failure.errno)
}

private inline fun withPathTempDirectory(block: (Path) -> Unit) {
    val temporary = Files.createTempDirectory("kwasm-wasi-path-jvm-")
    try {
        block(temporary)
    } finally {
        temporary.toFile().deleteRecursively()
    }
}
