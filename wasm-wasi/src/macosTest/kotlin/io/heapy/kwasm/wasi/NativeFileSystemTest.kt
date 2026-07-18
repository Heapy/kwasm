@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kwasm.wasi

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.rmdir
import platform.posix.symlink
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeFileSystemTest {
    @Test
    fun descriptorRelativeBackendReadsWritesCreatesAndConfinesPaths() = runBlocking {
        val temporary = createTemporaryDirectory()
        val rootPath = "$temporary/root"
        val outsidePath = "$temporary/outside"
        check(mkdir(rootPath, DIRECTORY_MODE) == 0)
        check(mkdir(outsidePath, DIRECTORY_MODE) == 0)

        val root = NativeFileSystem(rootPath)
        val outside = NativeFileSystem(outsidePath)
        try {
            val created = root.open(
                "message.txt",
                WasiPathOpenOptions(
                    create = true,
                    exclusive = true,
                    read = true,
                    write = true,
                ),
            ) as WasiOpenedFile
            assertEquals(5, created.file.write(0, "hello".encodeToByteArray()))
            assertEquals(5L, created.file.size())
            assertContentEquals(
                "hello".encodeToByteArray(),
                created.file.read(0, 32),
            )
            created.file.close()

            check(mkdir("$rootPath/nested", DIRECTORY_MODE) == 0)
            val nested = root.open(
                "nested",
                WasiPathOpenOptions(requireDirectory = true),
            ) as WasiOpenedDirectory
            val child = nested.directory.open(
                "child.txt",
                WasiPathOpenOptions(create = true, write = true),
            ) as WasiOpenedFile
            assertEquals(1, child.file.write(0, byteArrayOf(7)))
            child.file.close()

            val secret = outside.open(
                "secret.txt",
                WasiPathOpenOptions(create = true, write = true),
            ) as WasiOpenedFile
            secret.file.write(0, "secret".encodeToByteArray())
            secret.file.close()
            check(symlink("$outsidePath/secret.txt", "$rootPath/escape") == 0)
            check(symlink(outsidePath, "$rootPath/escape-directory") == 0)

            assertErrno(WasiErrno.NOTCAPABLE) {
                root.open("../outside/secret.txt", WasiPathOpenOptions(read = true))
            }
            assertErrno(WasiErrno.NOTCAPABLE) {
                root.open("$outsidePath/secret.txt", WasiPathOpenOptions(read = true))
            }
            assertErrno(WasiErrno.NOTCAPABLE) {
                root.open(
                    "escape",
                    WasiPathOpenOptions(read = true, followSymlinks = true),
                )
            }
            assertErrno(WasiErrno.NOTCAPABLE) {
                root.open(
                    "escape-directory/created.txt",
                    WasiPathOpenOptions(create = true, write = true),
                )
            }
            assertErrno(WasiErrno.NOENT) {
                outside.open("created.txt", WasiPathOpenOptions(read = true))
            }

            root.close()
            assertErrno(WasiErrno.BADF) {
                nested.directory.open("child.txt", WasiPathOpenOptions(read = true))
            }
        } finally {
            outside.close()
            root.close()
            unlink("$rootPath/escape")
            unlink("$rootPath/escape-directory")
            unlink("$rootPath/message.txt")
            unlink("$rootPath/nested/child.txt")
            unlink("$outsidePath/secret.txt")
            rmdir("$rootPath/nested")
            rmdir(rootPath)
            rmdir(outsidePath)
            rmdir(temporary)
        }
    }

    @Test
    fun descriptorRelativePathFamilyMutatesWithoutFollowingLinks() = runBlocking {
        val temporary = createTemporaryDirectory()
        val rootPath = "$temporary/root"
        check(mkdir(rootPath, DIRECTORY_MODE) == 0)
        val root = NativeFileSystem(rootPath)
        try {
            val source = root.open(
                "source.txt",
                WasiPathOpenOptions(create = true, write = true),
            ) as WasiOpenedFile
            source.file.write(0, "source".encodeToByteArray())
            source.file.close()

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
            assertErrno(WasiErrno.NOTCAPABLE) {
                root.stat("dir/link", followSymlinks = true)
            }

            root.setTimes(
                path = "source.txt",
                followSymlinks = false,
                accessTimeNanos = 1_000_000_000uL,
                modificationTimeNanos = 2_000_000_000uL,
            )
            val stat = root.stat("source.txt", followSymlinks = false)
            assertEquals(WasiFileType.REGULAR_FILE, stat.fileType)
            assertEquals(1_000_000_000uL, stat.attributes.accessTimeNanos)
            assertEquals(2_000_000_000uL, stat.attributes.modificationTimeNanos)

            root.link(
                sourcePath = "source.txt",
                followSourceSymlink = false,
                targetDirectory = root,
                targetPath = "dir/hard",
            )
            assertEquals(
                2uL,
                root.stat("source.txt", followSymlinks = false).attributes.linkCount,
            )
            root.rename("dir/hard", root, "dir/renamed")
            assertErrno(WasiErrno.NOENT) {
                root.stat("dir/hard", followSymlinks = false)
            }
            root.unlinkFile("dir/renamed")
            root.unlinkFile("dir/link")
            root.removeDirectory("dir")
            assertErrno(WasiErrno.NOENT) {
                root.stat("dir", followSymlinks = false)
            }
        } finally {
            root.close()
            unlink("$rootPath/dir/renamed")
            unlink("$rootPath/dir/hard")
            unlink("$rootPath/dir/link")
            unlink("$rootPath/source.txt")
            rmdir("$rootPath/dir")
            rmdir(rootPath)
            rmdir(temporary)
        }
    }

    @Test
    fun directoryMetadataEnumerationAndDotOpenPreserveCapability() = runBlocking {
        val temporary = createTemporaryDirectory()
        val rootPath = "$temporary/root"
        check(mkdir(rootPath, DIRECTORY_MODE) == 0)
        val root = NativeFileSystem(rootPath)
        try {
            val source = root.open(
                "source.txt",
                WasiPathOpenOptions(create = true, write = true),
            ) as WasiOpenedFile
            source.file.close()
            root.createDirectory("subdir")
            root.createSymbolicLink("source.txt", "link")
            repeat(BATCH_ENTRY_COUNT) { index ->
                val file = root.open(
                    "batch-${index.toString().padStart(3, '0')}",
                    WasiPathOpenOptions(create = true, exclusive = true, write = true),
                ) as WasiOpenedFile
                file.file.close()
            }

            val rootStat = root.statSelf()
            assertEquals(true, rootStat.inode > 0uL)
            val entries = root.readEntries()
            assertEquals(entries.map { it.name }.sorted(), entries.map { it.name })
            assertEquals(BATCH_ENTRY_COUNT + 3, entries.size)
            assertEquals(
                WasiFileType.SYMBOLIC_LINK,
                entries.single { it.name == "link" }.fileType,
            )
            assertEquals(
                WasiFileType.REGULAR_FILE,
                entries.single { it.name == "source.txt" }.fileType,
            )
            assertEquals(
                WasiFileType.DIRECTORY,
                entries.single { it.name == "subdir" }.fileType,
            )
            assertEquals(entries, root.readEntries())

            val self = root.open(
                ".",
                WasiPathOpenOptions(requireDirectory = true),
            ) as WasiOpenedDirectory
            assertEquals(rootStat.inode, self.directory.statSelf().inode)
            assertEquals(entries, self.directory.readEntries())
            assertErrno(WasiErrno.NOENT) {
                root.open("", WasiPathOpenOptions(requireDirectory = true))
            }

            val reopened = root.open(
                "source.txt",
                WasiPathOpenOptions(read = true),
            ) as WasiOpenedFile
            reopened.file.close()

            root.close()
            assertErrno(WasiErrno.BADF) {
                self.directory.statSelf()
            }
            assertErrno(WasiErrno.BADF) {
                self.directory.readEntries()
            }
        } finally {
            root.close()
            repeat(BATCH_ENTRY_COUNT) { index ->
                unlink("$rootPath/batch-${index.toString().padStart(3, '0')}")
            }
            unlink("$rootPath/link")
            unlink("$rootPath/source.txt")
            rmdir("$rootPath/subdir")
            rmdir(rootPath)
            rmdir(temporary)
        }
    }
}

private suspend fun assertErrno(
    expected: WasiErrno,
    block: suspend () -> Unit,
) {
    val failure = assertFailsWith<WasiFileSystemException> { block() }
    assertEquals(expected, failure.errno)
}

private fun createTemporaryDirectory(): String =
    memScoped {
        val template = "/tmp/kwasm-native-fs.XXXXXX".cstr.getPointer(this)
        val result = checkNotNull(mkdtemp(template))
        result.toKString()
    }

private const val DIRECTORY_MODE: UShort = 448u
private const val BATCH_ENTRY_COUNT: Int = 270
