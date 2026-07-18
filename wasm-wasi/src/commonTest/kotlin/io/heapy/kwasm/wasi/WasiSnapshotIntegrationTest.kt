package io.heapy.kwasm.wasi

import io.heapy.kwasm.Module
import io.heapy.kwasm.SnapshotStateException
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.StoreStatus
import io.heapy.kwasm.Value
import io.heapy.kwasm.snapshot.KwasmSnapshot
import io.heapy.kwasm.snapshot.SnapshotHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WasiSnapshotIntegrationTest {
    @Test
    fun descriptorOffsetPreopenAndCloseLifecycleRoundTrip(): Unit = runBlocking {
        val module = blockingReadModule()
        val sourceInput = BlockingInput()
        val sourceFile = TrackingFile("hello world".encodeToByteArray())
        val sourceDirectory = SingleFileDirectory(sourceFile)
        val sourceWasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", sourceDirectory)),
                standardInput = sourceInput,
            ),
        )
        val sourceStore = snapshotStore()
        val sourceInstance = sourceWasi.instantiate(sourceStore, module)
        val sourceMemory = sourceInstance.memories.single()
        val fileFd = openFile(sourceWasi, sourceMemory, "message.txt")
        assertEquals(4, fileFd)
        assertContentEquals(
            "hello".encodeToByteArray(),
            readFile(sourceWasi, sourceMemory, fileFd, 5),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                sourceWasi,
                "fd_fdstat_set_flags",
                i32(0),
                i32(WasiFdFlags.NONBLOCK),
            ),
        )
        val reducedInputRights = WasiRights.FD_READ
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                sourceWasi,
                "fd_fdstat_set_rights",
                i32(0),
                i64(reducedInputRights.toLong()),
                i64(0),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                sourceWasi,
                "fd_fdstat_set_flags",
                i32(2),
                i32(WasiFdFlags.APPEND),
            ),
        )
        val reducedErrorRights = WasiRights.FD_FILESTAT_GET
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                sourceWasi,
                "fd_fdstat_set_rights",
                i32(2),
                i64(reducedErrorRights.toLong()),
                i64(0),
            ),
        )

        sourceMemory.iovec(address = 0, buffer = 32, length = 1)
        val invocation = async { sourceInstance.invoke("run") }
        sourceStore.status.first { it == StoreStatus.InHostImport }

        val targetInput = BlockingInput()
        val targetOutput = BufferWasiOutput()
        val targetError = BufferWasiOutput()
        val targetFile = TrackingFile("hello world".encodeToByteArray())
        val targetDirectory = SingleFileDirectory(targetFile)
        val hooks = ResourceHooks(
            targetInput = targetInput,
            targetOutput = targetOutput,
            targetError = targetError,
            targetDirectory = targetDirectory,
            targetFile = targetFile,
        )
        val encoded = KwasmSnapshot.capture(sourceInstance, hooks)
        invocation.cancelAndJoin()

        val targetWasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", targetDirectory)),
                standardInput = targetInput,
                standardOutput = targetOutput,
                standardError = targetError,
            ),
        )
        val targetStore = snapshotStore()
        val targetInstance = targetWasi.instantiate(targetStore, module)
        KwasmSnapshot.restore(encoded, targetInstance, hooks)
        val targetMemory = targetInstance.memories.single()

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(targetWasi, "fd_prestat_get", i32(3), i32(400)),
        )
        assertEquals(5u, targetMemory.u32(404))
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(targetWasi, "fd_prestat_dir_name", i32(3), i32(416), i32(5)),
        )
        assertContentEquals("/data".encodeToByteArray(), targetMemory.load(416, 5))

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(targetWasi, "fd_fdstat_get", i32(0), i32(448)),
        )
        assertEquals(WasiFileType.CHARACTER_DEVICE.code, targetMemory.loadByte(448))
        assertEquals(WasiFdFlags.NONBLOCK, targetMemory.u16(450))
        assertEquals(reducedInputRights, targetMemory.u64(456))
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(targetWasi, "fd_fdstat_get", i32(2), i32(480)),
        )
        assertEquals(WasiFileType.CHARACTER_DEVICE.code, targetMemory.loadByte(480))
        assertEquals(WasiFdFlags.APPEND, targetMemory.u16(482))
        assertEquals(reducedErrorRights, targetMemory.u64(488))

        assertContentEquals(
            " world".encodeToByteArray(),
            readFile(targetWasi, targetMemory, fileFd, 6),
            "restored descriptor must continue from its captured offset",
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(targetWasi, "fd_close", i32(fileFd)),
        )
        assertTrue(targetFile.closed, "fd_close must close the rehydrated handle")
        assertEquals(1, targetFile.closeCalls)
        targetStore.cancel()
        sourceStore.cancel()
    }

    @Test
    fun renumberedDescriptorReducedRightsAndFlagsRoundTrip(): Unit = runBlocking {
        val module = blockingReadModule()
        val sourceInput = BlockingInput()
        val sourceFile = TrackingFile("payload".encodeToByteArray())
        val sourceDirectory = SingleFileDirectory(sourceFile)
        val sourceWasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", sourceDirectory)),
                standardInput = sourceInput,
            ),
        )
        val sourceStore = snapshotStore()
        val sourceInstance = sourceWasi.instantiate(sourceStore, module)
        val sourceMemory = sourceInstance.memories.single()
        val originalFd = openFile(sourceWasi, sourceMemory, "message.txt")
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                sourceWasi,
                "fd_fdstat_set_flags",
                i32(originalFd),
                i32(WasiFdFlags.APPEND),
            ),
        )
        val reducedRights = WasiRights.FD_SEEK or WasiRights.FD_TELL
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                sourceWasi,
                "fd_fdstat_set_rights",
                i32(originalFd),
                i64(reducedRights.toLong()),
                i64(0),
            ),
        )
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(sourceWasi, "fd_renumber", i32(originalFd), i32(1)),
        )

        sourceMemory.iovec(address = 0, buffer = 32, length = 1)
        val invocation = async { sourceInstance.invoke("run") }
        sourceStore.status.first { it == StoreStatus.InHostImport }

        val targetInput = BlockingInput()
        val targetOutput = BufferWasiOutput()
        val targetError = BufferWasiOutput()
        val targetFile = TrackingFile("payload".encodeToByteArray())
        val targetDirectory = SingleFileDirectory(targetFile)
        val hooks = ResourceHooks(
            targetInput = targetInput,
            targetOutput = targetOutput,
            targetError = targetError,
            targetDirectory = targetDirectory,
            targetFile = targetFile,
        )
        val encoded = KwasmSnapshot.capture(sourceInstance, hooks)
        invocation.cancelAndJoin()

        val targetWasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", targetDirectory)),
                standardInput = targetInput,
                standardOutput = targetOutput,
                standardError = targetError,
            ),
        )
        val targetStore = snapshotStore()
        val targetInstance = targetWasi.instantiate(targetStore, module)
        KwasmSnapshot.restore(encoded, targetInstance, hooks)
        val targetMemory = targetInstance.memories.single()

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(targetWasi, "fd_fdstat_get", i32(1), i32(512)),
        )
        assertEquals(WasiFileType.REGULAR_FILE.code, targetMemory.loadByte(512))
        assertEquals(WasiFdFlags.APPEND, targetMemory.u16(514))
        assertEquals(reducedRights, targetMemory.u64(520))
        assertEquals(
            WasiErrno.BADF.code,
            call(targetWasi, "fd_fdstat_get", i32(originalFd), i32(512)),
        )
        targetStore.cancel()
        sourceStore.cancel()
    }

    @Test
    fun openWasiResourcesFailExplicitlyWithoutWasiHooks(): Unit = runBlocking {
        val module = blockingReadModule()
        val input = BlockingInput()
        val wasi = WasiPreview1(WasiConfig(standardInput = input))
        val store = snapshotStore()
        val instance = wasi.instantiate(store, module)
        instance.memories.single().iovec(address = 0, buffer = 32, length = 1)
        val invocation = async { instance.invoke("run") }
        store.status.first { it == StoreStatus.InHostImport }

        val failure = assertFailsWith<SnapshotStateException> {
            KwasmSnapshot.capture(instance, ExternOnlyHooks)
        }
        assertTrue(failure.message.orEmpty().contains("WASI descriptor state"))
        assertTrue(failure.message.orEmpty().contains("fd 0"))
        assertTrue(failure.message.orEmpty().contains("WasiSnapshotResourceHooks"))
        invocation.cancelAndJoin()
        store.cancel()
    }

    @Test
    fun restoreRefusesUnavailableWasiRehydrationBeforeRuntimeMutation(): Unit =
        runBlocking {
            val module = blockingReadModule()
            val sourceInput = BlockingInput()
            val sourceWasi = WasiPreview1(WasiConfig(standardInput = sourceInput))
            val sourceStore = snapshotStore()
            val sourceInstance = sourceWasi.instantiate(sourceStore, module)
            sourceInstance.memories.single().iovec(address = 0, buffer = 32, length = 1)
            val invocation = async { sourceInstance.invoke("run") }
            sourceStore.status.first { it == StoreStatus.InHostImport }

            val targetInput = BlockingInput()
            val targetOutput = BufferWasiOutput()
            val targetError = BufferWasiOutput()
            val unusedFile = TrackingFile(ByteArray(0))
            val captureHooks = ResourceHooks(
                targetInput = targetInput,
                targetOutput = targetOutput,
                targetError = targetError,
                targetDirectory = SingleFileDirectory(unusedFile),
                targetFile = unusedFile,
            )
            val encoded = KwasmSnapshot.capture(sourceInstance, captureHooks)
            invocation.cancelAndJoin()

            val targetWasi = WasiPreview1(
                WasiConfig(
                    standardInput = targetInput,
                    standardOutput = targetOutput,
                    standardError = targetError,
                ),
            )
            val targetStore = snapshotStore()
            val targetInstance = targetWasi.instantiate(targetStore, module)
            targetInstance.memories.single().storeByte(17, 0x7A)

            val failure = assertFailsWith<SnapshotStateException> {
                KwasmSnapshot.restore(encoded, targetInstance, ExternOnlyHooks)
            }
            assertTrue(failure.message.orEmpty().contains("restoring WASI descriptor state"))
            assertTrue(failure.message.orEmpty().contains("WasiSnapshotResourceHooks"))
            assertEquals(0x7A, targetInstance.memories.single().loadByte(17))
            assertEquals(StoreStatus.Idle, targetStore.status.value)
            targetStore.cancel()
            sourceStore.cancel()
        }
}

private object ExternOnlyHooks : SnapshotHooks {
    override fun externalizeExternRef(handle: Int): ByteArray? = null
    override fun rehydrateExternRef(key: ByteArray): Int? = null
}

internal class ResourceHooks(
    private val targetInput: WasiInput,
    private val targetOutput: WasiOutput,
    private val targetError: WasiOutput,
    private val targetDirectory: WasiDirectory,
    private val targetFile: WasiFileHandle,
) : SnapshotHooks, WasiSnapshotResourceHooks {
    override fun externalizeExternRef(handle: Int): ByteArray? = null
    override fun rehydrateExternRef(key: ByteArray): Int? = null

    override fun externalizeInput(fd: Int, input: WasiInput): ByteArray =
        "input-$fd".encodeToByteArray()

    override fun rehydrateInput(fd: Int, key: ByteArray): WasiInput? =
        targetInput.takeIf { key.text() == "input-$fd" }

    override fun externalizeOutput(fd: Int, output: WasiOutput): ByteArray =
        "output-$fd".encodeToByteArray()

    override fun rehydrateOutput(fd: Int, key: ByteArray): WasiOutput? =
        when (key.text()) {
            "output-1" -> targetOutput
            "output-2" -> targetError
            else -> null
        }

    override fun externalizeDirectory(fd: Int, directory: WasiDirectory): ByteArray =
        "directory-$fd".encodeToByteArray()

    override fun rehydrateDirectory(fd: Int, key: ByteArray): WasiDirectory? =
        targetDirectory.takeIf { key.text() == "directory-$fd" }

    override fun externalizeFile(fd: Int, file: WasiFileHandle): ByteArray =
        "file-$fd".encodeToByteArray()

    override fun rehydrateFile(fd: Int, key: ByteArray): WasiFileHandle? =
        targetFile.takeIf { key.text() == "file-$fd" }
}

internal class BlockingInput : WasiInput {
    private val release = CompletableDeferred<ByteArray>()

    override suspend fun read(maximumBytes: Int): ByteArray {
        val bytes = release.await()
        return bytes.copyOf(minOf(maximumBytes, bytes.size))
    }
}

internal class TrackingFile(
    bytes: ByteArray,
) : WasiFileHandle {
    private var contents: ByteArray = bytes.copyOf()
    var closed: Boolean = false
        private set
    var closeCalls: Int = 0
        private set

    override suspend fun size(): Long {
        requireOpen()
        return contents.size.toLong()
    }

    override suspend fun read(position: Long, maximumBytes: Int): ByteArray {
        requireOpen()
        if (position >= contents.size) return ByteArray(0)
        val start = position.toInt()
        return contents.copyOfRange(
            start,
            minOf(contents.size, start + maximumBytes),
        )
    }

    override suspend fun write(position: Long, bytes: ByteArray): Int {
        requireOpen()
        val start = position.toInt()
        val end = start + bytes.size
        if (end > contents.size) contents = contents.copyOf(end)
        bytes.copyInto(contents, start)
        return bytes.size
    }

    override suspend fun close() {
        closeCalls++
        closed = true
    }

    private fun requireOpen() {
        if (closed) throw WasiFileSystemException(WasiErrno.BADF)
    }
}

internal class SingleFileDirectory(
    private val file: WasiFileHandle,
) : WasiDirectory {
    override suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource {
        if (path != "message.txt") throw WasiFileSystemException(WasiErrno.NOENT)
        return WasiOpenedFile(file)
    }
}

internal fun blockingReadModule(): Module = Module.decode(
    byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        // types: (i32, i32, i32, i32) -> i32; () -> i32
        0x01, 0x0D, 0x02,
        0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F,
        0x60, 0x00, 0x01, 0x7F,
        // import wasi_snapshot_preview1.fd_read as function 0
        0x02, 0x22, 0x01,
        0x16,
        0x77, 0x61, 0x73, 0x69, 0x5F, 0x73, 0x6E, 0x61, 0x70, 0x73,
        0x68, 0x6F, 0x74, 0x5F, 0x70, 0x72, 0x65, 0x76, 0x69, 0x65, 0x77, 0x31,
        0x07, 0x66, 0x64, 0x5F, 0x72, 0x65, 0x61, 0x64,
        0x00, 0x00,
        // one local function of type 1 and one memory
        0x03, 0x02, 0x01, 0x01,
        0x05, 0x03, 0x01, 0x00, 0x01,
        // export function 1 as run
        0x07, 0x07, 0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x01,
        // run: fd_read(0, 0, 1, 16)
        0x0A, 0x0E, 0x01, 0x0C, 0x00,
        0x41, 0x00,
        0x41, 0x00,
        0x41, 0x01,
        0x41, 0x10,
        0x10, 0x00,
        0x0B,
    ),
)

internal suspend fun openFile(
    wasi: WasiPreview1,
    memory: io.heapy.kwasm.MemoryInstance,
    path: String,
): Int {
    val pathBytes = path.encodeToByteArray()
    memory.store(64, pathBytes)
    val rights =
        WasiRights.FD_READ or WasiRights.FD_SEEK or WasiRights.FD_TELL or
            WasiRights.FD_FDSTAT_SET_FLAGS
    assertEquals(
        WasiErrno.SUCCESS.code,
        call(
            wasi,
            "path_open",
            i32(3),
            i32(0),
            i32(64),
            i32(pathBytes.size),
            i32(0),
            i64(rights.toLong()),
            i64(0),
            i32(0),
            i32(96),
        ),
    )
    return memory.u32(96).toInt()
}

internal suspend fun readFile(
    wasi: WasiPreview1,
    memory: io.heapy.kwasm.MemoryInstance,
    fd: Int,
    length: Int,
): ByteArray {
    memory.iovec(address = 128, buffer = 256, length = length)
    assertEquals(
        WasiErrno.SUCCESS.code,
        call(wasi, "fd_read", i32(fd), i32(128), i32(1), i32(112)),
    )
    val read = memory.u32(112).toInt()
    return memory.load(256, read)
}

internal suspend fun call(
    wasi: WasiPreview1,
    name: String,
    vararg arguments: Value,
): Int {
    val results = wasi.hostImport(name).fn.call(arguments.toList())
    return if (results.isEmpty()) 0 else (results.single() as Value.I32).v
}

internal fun i32(value: Int): Value = Value.I32(value)
internal fun i64(value: Long): Value = Value.I64(value)

internal fun io.heapy.kwasm.MemoryInstance.u32(address: Int): UInt {
    val bytes = load(address.toLong(), 4)
    return bytes[0].toUByte().toUInt() or
        (bytes[1].toUByte().toUInt() shl 8) or
        (bytes[2].toUByte().toUInt() shl 16) or
        (bytes[3].toUByte().toUInt() shl 24)
}

internal fun io.heapy.kwasm.MemoryInstance.u16(address: Int): Int {
    val bytes = load(address.toLong(), 2)
    return bytes[0].toUByte().toInt() or
        (bytes[1].toUByte().toInt() shl 8)
}

internal fun io.heapy.kwasm.MemoryInstance.u64(address: Int): ULong {
    val bytes = load(address.toLong(), 8)
    var value = 0uL
    bytes.forEachIndexed { index, byte ->
        value = value or (byte.toUByte().toULong() shl (index * 8))
    }
    return value
}

internal fun io.heapy.kwasm.MemoryInstance.iovec(
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

internal fun snapshotStore(): Store =
    Store(StoreConfig(canonicalizeNaNs = true))

private fun ByteArray.text(): String =
    decodeToString(throwOnInvalidSequence = true)
