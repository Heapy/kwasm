package io.heapy.kwasm.wasi

import io.heapy.kwasm.StoreStatus
import io.heapy.kwasm.snapshot.KwasmSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmWasiSnapshotIntegrationTest {
    @Test
    fun realFileHandleOffsetAndCloseAreRehydrated(): Unit =
        withSnapshotTempDirectory { temporary ->
            runBlocking {
                val root = Files.createDirectory(temporary.resolve("root"))
                Files.write(root.resolve("message.txt"), "hello world".encodeToByteArray())
                val module = blockingReadModule()
                val sourceFileSystem = JvmFileSystem(root)
                val sourceInput = BlockingInput()
                val sourceWasi = WasiPreview1(
                    WasiConfig(
                        preopens = listOf(WasiPreopen("/data", sourceFileSystem)),
                        standardInput = sourceInput,
                    ),
                )
                val sourceStore = snapshotStore()
                val sourceInstance = sourceWasi.instantiate(sourceStore, module)
                val sourceMemory = sourceInstance.memories.single()
                val fd = openFile(sourceWasi, sourceMemory, "message.txt")
                assertContentEquals(
                    "hello".encodeToByteArray(),
                    readFile(sourceWasi, sourceMemory, fd, 5),
                )

                sourceMemory.iovec(address = 0, buffer = 32, length = 1)
                val invocation = async { sourceInstance.invoke("run") }
                sourceStore.status.first { it == StoreStatus.InHostImport }

                val targetInput = BlockingInput()
                val targetOutput = BufferWasiOutput()
                val targetError = BufferWasiOutput()
                val targetFileSystem = JvmFileSystem(root)
                val targetHandle = (
                    targetFileSystem.open(
                        "message.txt",
                        WasiPathOpenOptions(read = true),
                    ) as WasiOpenedFile
                    ).file
                val hooks = ResourceHooks(
                    targetInput = targetInput,
                    targetOutput = targetOutput,
                    targetError = targetError,
                    targetDirectory = targetFileSystem,
                    targetFile = targetHandle,
                )
                val encoded = KwasmSnapshot.capture(sourceInstance, hooks)
                invocation.cancelAndJoin()
                assertEquals(
                    WasiErrno.SUCCESS.code,
                    call(sourceWasi, "fd_close", i32(fd)),
                )

                val targetWasi = WasiPreview1(
                    WasiConfig(
                        preopens = listOf(WasiPreopen("/data", targetFileSystem)),
                        standardInput = targetInput,
                        standardOutput = targetOutput,
                        standardError = targetError,
                    ),
                )
                val targetStore = snapshotStore()
                val targetInstance = targetWasi.instantiate(targetStore, module)
                KwasmSnapshot.restore(encoded, targetInstance, hooks)

                assertContentEquals(
                    " world".encodeToByteArray(),
                    readFile(targetWasi, targetInstance.memories.single(), fd, 6),
                )
                assertEquals(
                    WasiErrno.SUCCESS.code,
                    call(targetWasi, "fd_close", i32(fd)),
                )
                val closed = assertFailsWith<WasiFileSystemException> {
                    targetHandle.read(0, 1)
                }
                assertEquals(WasiErrno.BADF, closed.errno)
                targetStore.cancel()
                sourceStore.cancel()
            }
        }
}

private inline fun withSnapshotTempDirectory(block: (Path) -> Unit) {
    val temporary = Files.createTempDirectory("kwasm-wasi-snapshot-jvm-")
    try {
        block(temporary)
    } finally {
        temporary.toFile().deleteRecursively()
    }
}
