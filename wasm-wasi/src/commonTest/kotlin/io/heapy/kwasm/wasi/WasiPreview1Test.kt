package io.heapy.kwasm.wasi

import io.heapy.kwasm.Limits
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.MemoryType
import io.heapy.kwasm.Value
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasiPreview1Test {
    @Test
    fun configurationDefaultsToSystemEntropy() {
        assertTrue(WasiConfig().random === SystemWasiRandom)
    }

    @Test
    fun systemEntropyFillsDistinctBytes() {
        val first = ByteArray(32)
        val second = ByteArray(32)

        runImmediate {
            SystemWasiRandom.fill(first)
            SystemWasiRandom.fill(second)
        }

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun argumentsEnvironmentClocksAndRandomUsePreview1Layouts() {
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                arguments = listOf("app", "two words"),
                environment = linkedMapOf("MODE" to "test", "UNICODE" to "λ"),
                clock = FixedWasiClock(
                    realtimeNanos = 123_456u,
                    monotonicNanos = 789u,
                    resolution = 10u,
                ),
                random = WasiRandom { destination ->
                    destination.indices.forEach { destination[it] = (it * 3).toByte() }
                },
            ),
        )
        wasi.attach(memory)

        assertEquals(0, call(wasi, "args_sizes_get", i32(0), i32(4)))
        assertEquals(2u, memory.u32(0))
        assertEquals(14u, memory.u32(4))
        assertEquals(0, call(wasi, "args_get", i32(16), i32(64)))
        assertEquals(64u, memory.u32(16))
        assertEquals(68u, memory.u32(20))
        assertContentEquals("app\u0000two words\u0000".encodeToByteArray(), memory.load(64, 14))

        assertEquals(0, call(wasi, "environ_sizes_get", i32(8), i32(12)))
        assertEquals(2u, memory.u32(8))
        assertEquals("MODE=test\u0000UNICODE=λ\u0000".encodeToByteArray().size.toUInt(), memory.u32(12))
        assertEquals(0, call(wasi, "environ_get", i32(24), i32(96)))

        assertEquals(0, call(wasi, "clock_res_get", i32(0), i32(160)))
        assertEquals(10uL, memory.u64(160))
        assertEquals(0, call(wasi, "clock_time_get", i32(1), i64(1), i32(168)))
        assertEquals(789uL, memory.u64(168))
        assertEquals(WasiErrno.INVAL.code, call(wasi, "clock_res_get", i32(99), i32(176)))

        assertEquals(0, call(wasi, "random_get", i32(192), i32(5)))
        assertContentEquals(byteArrayOf(0, 3, 6, 9, 12), memory.load(192, 5))
    }

    @Test
    fun preopenConfinesPathOpenAndSupportsReadSeekWriteAndMetadata() {
        val fs = InMemoryFileSystem()
        fs.createDirectories("sandbox")
        fs.createDirectories("sandbox/subdir")
        fs.writeFile("sandbox/message.txt", "hello".encodeToByteArray())
        fs.writeFile("outside.txt", "secret".encodeToByteArray())
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", fs.directory("sandbox"))),
            ),
        )
        wasi.attach(memory)

        assertEquals(0, call(wasi, "fd_prestat_get", i32(3), i32(0)))
        assertEquals(0, memory.loadByte(0))
        assertEquals(5u, memory.u32(4))
        assertEquals(0, call(wasi, "fd_prestat_dir_name", i32(3), i32(16), i32(5)))
        assertContentEquals("/data".encodeToByteArray(), memory.load(16, 5))

        val path = "message.txt".encodeToByteArray()
        memory.store(64, path)
        val rights = WasiRights.FD_READ or WasiRights.FD_WRITE or WasiRights.FD_SEEK or
            WasiRights.FD_TELL or WasiRights.FD_FILESTAT_GET
        assertEquals(
            0,
            call(
                wasi,
                "path_open",
                i32(3),
                i32(0),
                i32(64),
                i32(path.size),
                i32(0),
                i64(rights.toLong()),
                i64(0),
                i32(0),
                i32(96),
            ),
        )
        val fd = memory.u32(96).toInt()
        assertEquals(0, call(wasi, "fd_fdstat_get", i32(fd), i32(384)))
        assertEquals(WasiFileType.REGULAR_FILE.code, memory.loadByte(384))
        assertEquals(rights, memory.u64(392))

        memory.iovec(address = 128, buffer = 256, length = 5)
        assertEquals(0, call(wasi, "fd_read", i32(fd), i32(128), i32(1), i32(112)))
        assertEquals(5u, memory.u32(112))
        assertContentEquals("hello".encodeToByteArray(), memory.load(256, 5))

        assertEquals(0, call(wasi, "fd_seek", i32(fd), i64(0), i32(0), i32(120)))
        assertEquals(0uL, memory.u64(120))
        memory.store(272, "H".encodeToByteArray())
        memory.iovec(address = 136, buffer = 272, length = 1)
        assertEquals(0, call(wasi, "fd_write", i32(fd), i32(136), i32(1), i32(116)))
        assertEquals(1u, memory.u32(116))
        assertContentEquals("Hello".encodeToByteArray(), fs.readFile("sandbox/message.txt"))
        assertEquals(0, call(wasi, "fd_close", i32(fd)))
        assertEquals(
            WasiErrno.BADF.code,
            call(wasi, "fd_read", i32(fd), i32(128), i32(1), i32(112)),
        )

        val escape = "../outside.txt".encodeToByteArray()
        memory.store(320, escape)
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                wasi,
                "path_open",
                i32(3),
                i32(0),
                i32(320),
                i32(escape.size),
                i32(0),
                i64(WasiRights.FD_READ.toLong()),
                i64(0),
                i32(0),
                i32(360),
            ),
        )
        assertContentEquals("secret".encodeToByteArray(), fs.readFile("outside.txt"))

        val trailingFile = "message.txt/".encodeToByteArray()
        memory.store(400, trailingFile)
        assertEquals(
            WasiErrno.NOTDIR.code,
            call(
                wasi,
                "path_open",
                i32(3),
                i32(0),
                i32(400),
                i32(trailingFile.size),
                i32(0),
                i64(WasiRights.FD_READ.toLong()),
                i64(0),
                i32(0),
                i32(432),
            ),
        )

        val trailingDirectory = "subdir///".encodeToByteArray()
        memory.store(448, trailingDirectory)
        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "path_open",
                i32(3),
                i32(0),
                i32(448),
                i32(trailingDirectory.size),
                i32(0),
                i64(WasiRights.FD_READDIR.toLong()),
                i64(0),
                i32(0),
                i32(480),
            ),
        )
    }

    @Test
    fun fdReaddirEncodesDirentsResumesCookiesAndReportsPartialRecords() {
        val fs = InMemoryFileSystem()
        fs.createDirectories("sandbox/subdir")
        fs.writeFile("sandbox/z.txt", byteArrayOf(1))
        fs.writeFile("sandbox/a.txt", byteArrayOf(2))
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                preopens = listOf(WasiPreopen("/data", fs.directory("sandbox"))),
            ),
        )
        wasi.attach(memory)

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_filestat_get", i32(3), i32(512)),
        )
        val directoryInode = memory.u64(520)

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_readdir", i32(3), i32(1024), i32(512), i64(0), i32(768)),
        )
        val entries = memory.directoryEntries(1024, memory.u32(768).toInt())
        assertEquals(listOf(".", "..", "a.txt", "subdir", "z.txt"), entries.map { it.name })
        assertEquals(listOf(1uL, 2uL, 3uL, 4uL, 5uL), entries.map { it.nextCookie })
        assertEquals(WasiFileType.DIRECTORY, entries[0].fileType)
        assertEquals(directoryInode, entries[0].inode)
        assertEquals(WasiFileType.DIRECTORY, entries[1].fileType)
        assertEquals(WasiFileType.REGULAR_FILE, entries[2].fileType)
        assertEquals(WasiFileType.DIRECTORY, entries[3].fileType)
        assertTrue(entries.drop(2).all { it.inode != 0uL })

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(
                wasi,
                "fd_readdir",
                i32(3),
                i32(2048),
                i32(512),
                i64(entries[1].nextCookie.toLong()),
                i32(772),
            ),
        )
        assertEquals(
            listOf("a.txt", "subdir", "z.txt"),
            memory.directoryEntries(2048, memory.u32(772).toInt()).map { it.name },
        )

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_readdir", i32(3), i32(3072), i32(26), i64(2), i32(776)),
        )
        assertEquals(26u, memory.u32(776))
        assertEquals(3uL, memory.u64(3072))
        assertEquals(5u, memory.u32(3088))
        assertEquals(WasiFileType.REGULAR_FILE.code, memory.loadByte(3092))
        assertContentEquals("a.".encodeToByteArray(), memory.load(3096, 2))

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "fd_readdir", i32(3), i32(4096), i32(64), i64(-1), i32(780)),
        )
        assertEquals(0u, memory.u32(780))

        val restrictedMemory = memory()
        val restricted = WasiPreview1(
            WasiConfig(
                preopens = listOf(
                    WasiPreopen(
                        guestPath = "/data",
                        directory = fs.directory("sandbox"),
                        rightsBase = WasiRights.FD_FILESTAT_GET,
                    ),
                ),
            ),
        )
        restricted.attach(restrictedMemory)
        assertEquals(
            WasiErrno.NOTCAPABLE.code,
            call(
                restricted,
                "fd_readdir",
                i32(3),
                i32(1024),
                i32(64),
                i64(0),
                i32(768),
            ),
        )
        assertEquals(
            WasiErrno.NOTDIR.code,
            call(wasi, "fd_readdir", i32(1), i32(1024), i32(64), i64(0), i32(768)),
        )
    }

    @Test
    fun sockShutdownDistinguishesMissingAndNonSocketDescriptors() {
        val memory = memory()
        val wasi = WasiPreview1()
        wasi.attach(memory)

        assertEquals(
            WasiErrno.BADF.code,
            call(wasi, "sock_shutdown", i32(99), i32(1)),
        )
        assertEquals(
            WasiErrno.NOTSOCK.code,
            call(wasi, "sock_shutdown", i32(1), i32(3)),
        )
    }

    @Test
    fun pollOneoffUsesPaddedClockSubscriptionAndEventLayouts() {
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                clock = FixedWasiClock(
                    realtimeNanos = 5_000u,
                    monotonicNanos = 1_000u,
                ),
            ),
        )
        wasi.attach(memory)
        memory.clockSubscription(
            address = 1024,
            userdata = 0x0123_4567_89AB_CDEFu,
            clockId = 1,
            timeoutNanos = 0u,
        )
        memory.clockSubscription(
            address = 1072,
            userdata = 0x0FED_CBA9_7654_3210u,
            clockId = 1,
            timeoutNanos = 999u,
            absolute = true,
        )

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "poll_oneoff", i32(1024), i32(2048), i32(2), i32(768)),
        )
        assertEquals(2u, memory.u32(768))
        val events = memory.pollEvents(2048, 2)
        assertEquals(
            listOf(0x0123_4567_89AB_CDEFu, 0x0FED_CBA9_7654_3210u),
            events.map { it.userdata },
        )
        assertEquals(listOf(WasiErrno.SUCCESS.code, WasiErrno.SUCCESS.code), events.map { it.error })
        assertEquals(listOf(0, 0), events.map { it.type })
        assertEquals(listOf(0uL, 0uL), events.map { it.readableBytes })

        assertEquals(
            WasiErrno.INVAL.code,
            call(wasi, "poll_oneoff", i32(1024), i32(2048), i32(0), i32(768)),
        )
    }

    @Test
    fun pollOneoffReportsImmediateStdioReadinessAndDescriptorErrors() {
        val memory = memory()
        val wasi = WasiPreview1()
        wasi.attach(memory)
        memory.fdSubscription(1024, userdata = 11u, type = 1, fd = 0)
        memory.fdSubscription(1072, userdata = 22u, type = 2, fd = 1)
        memory.fdSubscription(1120, userdata = 33u, type = 1, fd = 999)
        memory.fdSubscription(1168, userdata = 44u, type = 2, fd = 0)

        assertEquals(
            WasiErrno.SUCCESS.code,
            call(wasi, "poll_oneoff", i32(1024), i32(2048), i32(4), i32(768)),
        )
        assertEquals(4u, memory.u32(768))
        val events = memory.pollEvents(2048, 4)
        assertEquals(listOf(11uL, 22uL, 33uL, 44uL), events.map { it.userdata })
        assertEquals(listOf(1, 2, 1, 2), events.map { it.type })
        assertEquals(
            listOf(
                WasiErrno.SUCCESS.code,
                WasiErrno.SUCCESS.code,
                WasiErrno.BADF.code,
                WasiErrno.NOTCAPABLE.code,
            ),
            events.map { it.error },
        )
    }

    @Test
    fun standardIoIsSuspendableAndDoesNotBlockTheCaller() {
        lateinit var pendingRead: Continuation<ByteArray>
        val output = BufferWasiOutput()
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                standardInput = WasiInput { suspendCoroutine { pendingRead = it } },
                standardOutput = output,
            ),
        )
        wasi.attach(memory)
        memory.iovec(address = 0, buffer = 64, length = 8)

        var completed = false
        var errno = -1
        suspend {
            errno = callSuspend(wasi, "fd_read", i32(0), i32(0), i32(1), i32(32))
        }.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    result.getOrThrow()
                    completed = true
                }
            },
        )

        assertFalse(completed)
        pendingRead.resume("parked".encodeToByteArray())
        assertTrue(completed)
        assertEquals(0, errno)
        assertEquals(6u, memory.u32(32))
        assertContentEquals("parked".encodeToByteArray(), memory.load(64, 6))

        memory.store(80, "output".encodeToByteArray())
        memory.iovec(address = 8, buffer = 80, length = 6)
        assertEquals(0, call(wasi, "fd_write", i32(1), i32(8), i32(1), i32(36)))
        assertContentEquals("output".encodeToByteArray(), output.bytes())
    }

    @Test
    fun badPointersReturnFaultAndProcExitCarriesUnsignedCode() {
        val memory = memory()
        val wasi = WasiPreview1()
        wasi.attach(memory)

        assertEquals(
            WasiErrno.FAULT.code,
            call(wasi, "random_get", i32(memory.byteSize - 1), i32(2)),
        )
        assertEquals(0, call(wasi, "sched_yield"))
        val exit = assertFailsWith<WasiProcessExit> {
            call(wasi, "proc_exit", i32(-1))
        }
        assertEquals(UInt.MAX_VALUE, exit.exitCode)
    }

    @Test
    fun unavailableSystemEntropyReturnsIoWithoutWritingGuestMemory() {
        val memory = memory()
        val wasi = WasiPreview1(
            WasiConfig(
                random = WasiRandom {
                    throw WasiRandomException("test entropy failure")
                },
            ),
        )
        wasi.attach(memory)
        memory.store(32, byteArrayOf(1, 2, 3, 4))

        assertEquals(
            WasiErrno.IO.code,
            call(wasi, "random_get", i32(32), i32(4)),
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4), memory.load(32, 4))
    }

    private fun call(
        wasi: WasiPreview1,
        name: String,
        vararg arguments: Value,
    ): Int = runImmediate { callSuspend(wasi, name, *arguments) }

    private suspend fun callSuspend(
        wasi: WasiPreview1,
        name: String,
        vararg arguments: Value,
    ): Int {
        val results = wasi.hostImport(name).fn.call(arguments.toList())
        return if (results.isEmpty()) 0 else (results.single() as Value.I32).v
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

    private fun MemoryInstance.u16(address: Int): Int {
        val bytes = load(address.toLong(), 2)
        return bytes[0].toUByte().toInt() or
            (bytes[1].toUByte().toInt() shl 8)
    }

    private fun MemoryInstance.directoryEntries(
        address: Int,
        length: Int,
    ): List<DecodedDirectoryEntry> {
        val entries = mutableListOf<DecodedDirectoryEntry>()
        var current = address
        val end = address + length
        while (end - current >= 24) {
            val nameLength = u32(current + 16).toInt()
            if (nameLength > end - current - 24) break
            entries += DecodedDirectoryEntry(
                nextCookie = u64(current),
                inode = u64(current + 8),
                fileType = WasiFileType.entries.single {
                    it.code == loadByte((current + 20).toLong())
                },
                name = load((current + 24).toLong(), nameLength)
                    .decodeToString(throwOnInvalidSequence = true),
            )
            current += 24 + nameLength
        }
        return entries
    }

    private fun MemoryInstance.clockSubscription(
        address: Int,
        userdata: ULong,
        clockId: Int,
        timeoutNanos: ULong,
        absolute: Boolean = false,
    ) {
        store(address.toLong(), ByteArray(48))
        putU64(address, userdata)
        storeByte((address + 8).toLong(), 0)
        putU32(address + 16, clockId.toUInt())
        putU64(address + 24, timeoutNanos)
        putU64(address + 32, 0u)
        putU16(address + 40, if (absolute) 1 else 0)
    }

    private fun MemoryInstance.fdSubscription(
        address: Int,
        userdata: ULong,
        type: Int,
        fd: Int,
    ) {
        store(address.toLong(), ByteArray(48))
        putU64(address, userdata)
        storeByte((address + 8).toLong(), type)
        putU32(address + 16, fd.toUInt())
    }

    private fun MemoryInstance.pollEvents(
        address: Int,
        count: Int,
    ): List<DecodedPollEvent> =
        List(count) { index ->
            val eventAddress = address + index * 32
            DecodedPollEvent(
                userdata = u64(eventAddress),
                error = u16(eventAddress + 8),
                type = loadByte((eventAddress + 10).toLong()),
                readableBytes = u64(eventAddress + 16),
                flags = u16(eventAddress + 24),
            )
        }

    private fun MemoryInstance.putU16(
        address: Int,
        value: Int,
    ) {
        store(
            address.toLong(),
            byteArrayOf(value.toByte(), (value ushr 8).toByte()),
        )
    }

    private fun MemoryInstance.putU32(
        address: Int,
        value: UInt,
    ) {
        store(
            address.toLong(),
            ByteArray(4) { index -> (value shr (index * 8)).toByte() },
        )
    }

    private fun MemoryInstance.putU64(
        address: Int,
        value: ULong,
    ) {
        store(
            address.toLong(),
            ByteArray(8) { index -> (value shr (index * 8)).toByte() },
        )
    }

    private fun MemoryInstance.iovec(address: Int, buffer: Int, length: Int) {
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

    private data class DecodedDirectoryEntry(
        val nextCookie: ULong,
        val inode: ULong,
        val fileType: WasiFileType,
        val name: String,
    )

    private data class DecodedPollEvent(
        val userdata: ULong,
        val error: Int,
        val type: Int,
        val readableBytes: ULong,
        val flags: Int,
    )
}

private fun <T> runImmediate(block: suspend () -> T): T {
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
