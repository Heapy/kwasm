package io.heapy.kwasm.wasi

import io.heapy.kwasm.ExecutionTrap
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.HostSnapshotHooks
import io.heapy.kwasm.HostSnapshotRestore
import io.heapy.kwasm.HostFunction
import io.heapy.kwasm.HostImport
import io.heapy.kwasm.ImportDesc
import io.heapy.kwasm.Instance
import io.heapy.kwasm.InstanceScopedHostSnapshotParticipant
import io.heapy.kwasm.LinkException
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.SnapshotStateException
import io.heapy.kwasm.Store
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Stateful WASI Preview 1 host module.
 *
 * A host object represents one guest process: it owns the descriptor table and
 * is intentionally coroutine-confined just like a core [Store]. Nothing is
 * visible to the guest except [WasiConfig.preopens] and the three configured
 * standard streams.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasiPreview1(
    public val config: WasiConfig = WasiConfig(),
) {
    private var attachedMemory: MemoryInstance? = null
    private var attachedInstance: Instance? = null
    private val descriptors: MutableMap<Int, Descriptor> = linkedMapOf()
    private var descriptorTableMutated: Boolean = false
    private val encodedArguments: List<ByteArray> =
        config.arguments.map { (it.encodeToByteArray() + 0) }
    private val encodedEnvironment: List<ByteArray> =
        config.environment.map { (name, value) -> ("$name=$value".encodeToByteArray() + 0) }
    private val functions: Map<String, HostImport> = createFunctions()
    private val snapshotParticipant: InstanceScopedHostSnapshotParticipant =
        object : InstanceScopedHostSnapshotParticipant {
            override val id: String = WASI_SNAPSHOT_PARTICIPANT_ID

            override fun capture(
                instance: Instance,
                hooks: HostSnapshotHooks?,
            ): ByteArray {
                requireAttachedInstance(instance)
                return captureDescriptorState(hooks)
            }

            override fun prepareRestore(
                payload: ByteArray,
                instance: Instance,
                hooks: HostSnapshotHooks?,
            ): HostSnapshotRestore {
                requireAttachedInstance(instance)
                return prepareDescriptorRestore(payload, hooks)
            }
        }

    init {
        descriptors[0] = InputDescriptor(config.standardInput)
        descriptors[1] = OutputDescriptor(config.standardOutput)
        descriptors[2] = OutputDescriptor(config.standardError)
        config.preopens.forEachIndexed { index, preopen ->
            descriptors[index + FIRST_PREOPEN_FD] = DirectoryDescriptor(
                directory = preopen.directory,
                preopenName = preopen.guestPath.encodeToByteArray(),
                rightsBase = preopen.rightsBase,
                rightsInheriting = preopen.rightsInheriting,
            )
        }
    }

    /** Names implemented by this host module. */
    public val supportedImports: Set<String> get() = functions.keys

    /**
     * Bind host calls to a guest linear memory.
     *
     * This overload cannot associate descriptor state with a [Store]. Use
     * [attach] with an [Instance] when snapshots are required.
     */
    public fun attach(memory: MemoryInstance) {
        attachedMemory = memory
    }

    /**
     * Bind host calls to memory zero of [instance] and register the WASI
     * descriptor table as store-owned snapshot state.
     */
    public fun attach(instance: Instance) {
        val previous = attachedInstance
        if (previous != null && previous !== instance) {
            throw SnapshotStateException(
                "one WasiPreview1 process cannot be attached to multiple instances",
            )
        }
        val memory = instance.memories.firstOrNull()
            ?: throw LinkException("WASI requires guest memory 0")
        attach(memory)
        attachedInstance = instance
        instance.store.registerHostSnapshotParticipant(instance, snapshotParticipant)
    }

    /** Return one canonical typed host import by its Preview 1 field name. */
    public fun hostImport(name: String): HostImport =
        functions[name]
            ?: throw LinkException(
                "unsupported WASI Preview 1 import '$name'",
                WASI_SNAPSHOT_PREVIEW1,
                name,
            )

    /**
     * Resolve the function imports of a module containing only WASI function
     * imports. The result preserves WebAssembly function-index order.
     */
    public fun resolve(module: Module): ResolvedImports {
        val resolved = mutableListOf<HostImport>()
        module.imports.forEach { import ->
            val function = import.desc as? ImportDesc.Function
                ?: throw LinkException(
                    "WASI resolver cannot provide a non-function import",
                    import.module,
                    import.field,
                )
            if (import.module != WASI_SNAPSHOT_PREVIEW1) {
                throw LinkException(
                    "no provider configured for this import module",
                    import.module,
                    import.field,
                )
            }
            val host = hostImport(import.field)
            val requiredType = module.types[function.typeIndex]
            if (host.type != requiredType) {
                throw LinkException(
                    "type mismatch: guest requires $requiredType but WASI defines ${host.type}",
                    import.module,
                    import.field,
                )
            }
            resolved += host
        }
        return ResolvedImports(functions = resolved)
    }

    /**
     * Instantiate a module, resolve its WASI imports, then attach memory zero.
     * The start function is deliberately not run automatically.
     */
    public fun instantiate(
        store: Store,
        module: Module,
    ): Instance =
        Instance.instantiate(store, module, resolve(module)).also(::attach)

    private fun createFunctions(): Map<String, HostImport> = linkedMapOf(
        "args_get" to errnoImport(i32, i32) { memory, args ->
            writeStringVector(memory, ptr(args, 0), ptr(args, 1), encodedArguments)
        },
        "args_sizes_get" to errnoImport(i32, i32) { memory, args ->
            writeStringSizes(memory, ptr(args, 0), ptr(args, 1), encodedArguments)
        },
        "environ_get" to errnoImport(i32, i32) { memory, args ->
            writeStringVector(memory, ptr(args, 0), ptr(args, 1), encodedEnvironment)
        },
        "environ_sizes_get" to errnoImport(i32, i32) { memory, args ->
            writeStringSizes(memory, ptr(args, 0), ptr(args, 1), encodedEnvironment)
        },
        "clock_res_get" to errnoImport(i32, i32) { memory, args ->
            val resolution = config.clock.resolutionNanos(i32(args, 0))
                ?: fail(WasiErrno.INVAL)
            memory.writeU64(ptr(args, 1), resolution)
            WasiErrno.SUCCESS
        },
        "clock_time_get" to errnoImport(i32, i64, i32) { memory, args ->
            val time = config.clock.timeNanos(i32(args, 0), i64(args, 1).toULong())
                ?: fail(WasiErrno.INVAL)
            memory.writeU64(ptr(args, 2), time)
            WasiErrno.SUCCESS
        },
        "fd_advise" to errnoImport(i32, i64, i64, i32) { _, args ->
            fdAdvise(
                fd = i32(args, 0),
                offset = i64(args, 1).toULong(),
                length = i64(args, 2).toULong(),
                advice = i32(args, 3),
            )
        },
        "fd_allocate" to errnoImport(i32, i64, i64) { _, args ->
            fdAllocate(
                fd = i32(args, 0),
                offset = i64(args, 1).toULong(),
                length = i64(args, 2).toULong(),
            )
        },
        "fd_close" to errnoImport(i32) { _, args ->
            val fd = i32(args, 0)
            val descriptor = descriptors.remove(fd) ?: fail(WasiErrno.BADF)
            descriptorTableMutated = true
            descriptor.close()
            WasiErrno.SUCCESS
        },
        "fd_datasync" to errnoImport(i32) { _, args ->
            fdSync(i32(args, 0), dataOnly = true)
        },
        "fd_fdstat_get" to errnoImport(i32, i32) { memory, args ->
            fdFdstatGet(memory, i32(args, 0), ptr(args, 1))
        },
        "fd_fdstat_set_flags" to errnoImport(i32, i32) { _, args ->
            fdFdstatSetFlags(i32(args, 0), i32(args, 1))
        },
        "fd_fdstat_set_rights" to errnoImport(i32, i64, i64) { _, args ->
            fdFdstatSetRights(
                fd = i32(args, 0),
                rightsBase = i64(args, 1).toULong(),
                rightsInheriting = i64(args, 2).toULong(),
            )
        },
        "fd_filestat_get" to errnoImport(i32, i32) { memory, args ->
            fdFilestatGet(memory, i32(args, 0), ptr(args, 1))
        },
        "fd_filestat_set_size" to errnoImport(i32, i64) { _, args ->
            fdFilestatSetSize(i32(args, 0), i64(args, 1).toULong())
        },
        "fd_filestat_set_times" to errnoImport(i32, i64, i64, i32) { _, args ->
            fdFilestatSetTimes(
                fd = i32(args, 0),
                accessTimeNanos = i64(args, 1).toULong(),
                modificationTimeNanos = i64(args, 2).toULong(),
                flags = i32(args, 3),
            )
        },
        "fd_pread" to errnoImport(i32, i32, i32, i64, i32) { memory, args ->
            fdPread(
                memory = memory,
                fd = i32(args, 0),
                iovecsAddress = ptr(args, 1),
                iovecCount = size(args, 2),
                offset = i64(args, 3).toULong(),
                resultAddress = ptr(args, 4),
            )
        },
        "fd_prestat_get" to errnoImport(i32, i32) { memory, args ->
            fdPrestatGet(memory, i32(args, 0), ptr(args, 1))
        },
        "fd_prestat_dir_name" to errnoImport(i32, i32, i32) { memory, args ->
            fdPrestatDirName(memory, i32(args, 0), ptr(args, 1), size(args, 2))
        },
        "fd_pwrite" to errnoImport(i32, i32, i32, i64, i32) { memory, args ->
            fdPwrite(
                memory = memory,
                fd = i32(args, 0),
                iovecsAddress = ptr(args, 1),
                iovecCount = size(args, 2),
                offset = i64(args, 3).toULong(),
                resultAddress = ptr(args, 4),
            )
        },
        "fd_read" to errnoImport(i32, i32, i32, i32) { memory, args ->
            fdRead(
                memory = memory,
                fd = i32(args, 0),
                iovecsAddress = ptr(args, 1),
                iovecCount = size(args, 2),
                resultAddress = ptr(args, 3),
            )
        },
        "fd_readdir" to errnoImport(i32, i32, i32, i64, i32) { memory, args ->
            fdReaddir(
                memory = memory,
                fd = i32(args, 0),
                bufferAddress = ptr(args, 1),
                bufferLength = size(args, 2),
                cookie = i64(args, 3).toULong(),
                resultAddress = ptr(args, 4),
            )
        },
        "fd_renumber" to errnoImport(i32, i32) { _, args ->
            fdRenumber(i32(args, 0), i32(args, 1))
        },
        "fd_seek" to errnoImport(i32, i64, i32, i32) { memory, args ->
            fdSeek(
                memory = memory,
                fd = i32(args, 0),
                offset = i64(args, 1),
                whence = i32(args, 2),
                resultAddress = ptr(args, 3),
            )
        },
        "fd_sync" to errnoImport(i32) { _, args ->
            fdSync(i32(args, 0), dataOnly = false)
        },
        "fd_tell" to errnoImport(i32, i32) { memory, args ->
            fdTell(memory, i32(args, 0), ptr(args, 1))
        },
        "fd_write" to errnoImport(i32, i32, i32, i32) { memory, args ->
            fdWrite(
                memory = memory,
                fd = i32(args, 0),
                iovecsAddress = ptr(args, 1),
                iovecCount = size(args, 2),
                resultAddress = ptr(args, 3),
            )
        },
        "path_create_directory" to errnoImport(i32, i32, i32) { memory, args ->
            pathCreateDirectory(
                memory = memory,
                directoryFd = i32(args, 0),
                pathAddress = ptr(args, 1),
                pathLength = size(args, 2),
            )
        },
        "path_filestat_get" to errnoImport(i32, i32, i32, i32, i32) { memory, args ->
            pathFilestatGet(
                memory = memory,
                directoryFd = i32(args, 0),
                lookupFlags = i32(args, 1),
                pathAddress = ptr(args, 2),
                pathLength = size(args, 3),
                resultAddress = ptr(args, 4),
            )
        },
        "path_filestat_set_times" to
            errnoImport(i32, i32, i32, i32, i64, i64, i32) { memory, args ->
                pathFilestatSetTimes(
                    memory = memory,
                    directoryFd = i32(args, 0),
                    lookupFlags = i32(args, 1),
                    pathAddress = ptr(args, 2),
                    pathLength = size(args, 3),
                    accessTimeNanos = i64(args, 4).toULong(),
                    modificationTimeNanos = i64(args, 5).toULong(),
                    flags = i32(args, 6),
                )
            },
        "path_link" to errnoImport(i32, i32, i32, i32, i32, i32, i32) {
                memory,
                args,
            ->
            pathLink(
                memory = memory,
                sourceDirectoryFd = i32(args, 0),
                sourceLookupFlags = i32(args, 1),
                sourcePathAddress = ptr(args, 2),
                sourcePathLength = size(args, 3),
                targetDirectoryFd = i32(args, 4),
                targetPathAddress = ptr(args, 5),
                targetPathLength = size(args, 6),
            )
        },
        "path_open" to errnoImport(i32, i32, i32, i32, i32, i64, i64, i32, i32) {
                memory,
                args,
            ->
            pathOpen(
                memory = memory,
                directoryFd = i32(args, 0),
                lookupFlags = i32(args, 1),
                pathAddress = ptr(args, 2),
                pathLength = size(args, 3),
                openFlags = i32(args, 4),
                rightsBase = i64(args, 5).toULong(),
                rightsInheriting = i64(args, 6).toULong(),
                fdFlags = i32(args, 7),
                resultAddress = ptr(args, 8),
            )
        },
        "path_readlink" to errnoImport(i32, i32, i32, i32, i32, i32) { memory, args ->
            pathReadLink(
                memory = memory,
                directoryFd = i32(args, 0),
                pathAddress = ptr(args, 1),
                pathLength = size(args, 2),
                bufferAddress = ptr(args, 3),
                bufferLength = size(args, 4),
                resultAddress = ptr(args, 5),
            )
        },
        "path_remove_directory" to errnoImport(i32, i32, i32) { memory, args ->
            pathRemoveDirectory(
                memory = memory,
                directoryFd = i32(args, 0),
                pathAddress = ptr(args, 1),
                pathLength = size(args, 2),
            )
        },
        "path_rename" to errnoImport(i32, i32, i32, i32, i32, i32) { memory, args ->
            pathRename(
                memory = memory,
                sourceDirectoryFd = i32(args, 0),
                sourcePathAddress = ptr(args, 1),
                sourcePathLength = size(args, 2),
                targetDirectoryFd = i32(args, 3),
                targetPathAddress = ptr(args, 4),
                targetPathLength = size(args, 5),
            )
        },
        "path_symlink" to errnoImport(i32, i32, i32, i32, i32) { memory, args ->
            pathSymbolicLink(
                memory = memory,
                targetPathAddress = ptr(args, 0),
                targetPathLength = size(args, 1),
                directoryFd = i32(args, 2),
                linkPathAddress = ptr(args, 3),
                linkPathLength = size(args, 4),
            )
        },
        "path_unlink_file" to errnoImport(i32, i32, i32) { memory, args ->
            pathUnlinkFile(
                memory = memory,
                directoryFd = i32(args, 0),
                pathAddress = ptr(args, 1),
                pathLength = size(args, 2),
            )
        },
        "poll_oneoff" to errnoImport(i32, i32, i32, i32) { memory, args ->
            pollOneoff(
                memory = memory,
                subscriptionsAddress = ptr(args, 0),
                eventsAddress = ptr(args, 1),
                subscriptionCount = size(args, 2),
                resultAddress = ptr(args, 3),
            )
        },
        "proc_exit" to HostImport(
            FuncType(listOf(i32), emptyList()),
            HostFunction { args -> throw WasiProcessExit(i32(args, 0).toUInt()) },
        ),
        "random_get" to errnoImport(i32, i32) { memory, args ->
            randomGet(memory, ptr(args, 0), size(args, 1))
        },
        "sched_yield" to errnoImport { _, _ ->
            yield()
            WasiErrno.SUCCESS
        },
        "sock_shutdown" to errnoImport(i32, i32) { _, args ->
            sockShutdown(i32(args, 0))
        },
    )

    private fun errnoImport(
        vararg parameters: ValType,
        body: suspend (GuestMemory, List<Value>) -> WasiErrno,
    ): HostImport = HostImport(
        type = FuncType(parameters.toList(), listOf(i32)),
        fn = HostFunction { arguments ->
            val errno =
                try {
                    body(guestMemory(), arguments)
                } catch (failure: WasiFileSystemException) {
                    failure.errno
                } catch (_: ExecutionTrap) {
                    WasiErrno.FAULT
                }
            listOf(Value.I32(errno.code))
        },
    )

    private fun guestMemory(): GuestMemory =
        GuestMemory(
            attachedMemory
                ?: throw LinkException("WASI host is not attached to guest memory 0"),
        )

    private fun writeStringSizes(
        memory: GuestMemory,
        countAddress: Long,
        sizeAddress: Long,
        strings: List<ByteArray>,
    ): WasiErrno {
        val byteCount = strings.fold(0uL) { total, bytes -> total + bytes.size.toULong() }
        if (strings.size.toULong() > UInt.MAX_VALUE.toULong() ||
            byteCount > UInt.MAX_VALUE.toULong()
        ) {
            fail(WasiErrno.OVERFLOW)
        }
        memory.check(countAddress, 4)
        memory.check(sizeAddress, 4)
        memory.writeU32(countAddress, strings.size.toUInt())
        memory.writeU32(sizeAddress, byteCount.toUInt())
        return WasiErrno.SUCCESS
    }

    private fun writeStringVector(
        memory: GuestMemory,
        pointerTableAddress: Long,
        bufferAddress: Long,
        strings: List<ByteArray>,
    ): WasiErrno {
        val tableSize = checkedProduct(strings.size, 4)
        val dataSize = strings.fold(0L) { total, bytes ->
            checkedAdd(total, bytes.size.toLong())
        }
        if (dataSize > UInt.MAX_VALUE.toLong()) fail(WasiErrno.OVERFLOW)
        memory.check(pointerTableAddress, tableSize)
        memory.check(bufferAddress, dataSize.toInt())

        var current = bufferAddress
        strings.forEachIndexed { index, bytes ->
            if (current > UInt.MAX_VALUE.toLong()) fail(WasiErrno.OVERFLOW)
            memory.writeU32(pointerTableAddress + index * 4L, current.toUInt())
            memory.write(current, bytes)
            current += bytes.size
        }
        return WasiErrno.SUCCESS
    }

    private suspend fun fdAdvise(
        fd: Int,
        offset: ULong,
        length: ULong,
        advice: Int,
    ): WasiErrno {
        if (advice !in ADVICE_NORMAL..ADVICE_NOREUSE) fail(WasiErrno.INVAL)
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_ADVISE)
        val file = descriptor.regularFile()
        val signedOffset = signedFileSize(offset, WasiErrno.OVERFLOW)
        val signedLength = signedFileSize(length, WasiErrno.OVERFLOW)
        if (signedOffset > Long.MAX_VALUE - signedLength) fail(WasiErrno.OVERFLOW)
        file.file.advise(signedOffset, signedLength, advice)
        return WasiErrno.SUCCESS
    }

    private suspend fun fdAllocate(
        fd: Int,
        offset: ULong,
        length: ULong,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_ALLOCATE)
        val file = descriptor.regularFile()
        val signedOffset = signedFileSize(offset, WasiErrno.FBIG)
        val signedLength = signedFileSize(length, WasiErrno.FBIG)
        if (signedOffset > Long.MAX_VALUE - signedLength) fail(WasiErrno.FBIG)
        file.file.allocate(signedOffset, signedLength)
        return WasiErrno.SUCCESS
    }

    private suspend fun fdSync(
        fd: Int,
        dataOnly: Boolean,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(
            if (dataOnly) WasiRights.FD_DATASYNC else WasiRights.FD_SYNC,
        )
        descriptor.regularFile().file.sync(dataOnly)
        return WasiErrno.SUCCESS
    }

    private fun fdFdstatGet(
        memory: GuestMemory,
        fd: Int,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        memory.check(resultAddress, FDSTAT_SIZE)
        memory.zero(resultAddress, FDSTAT_SIZE)
        memory.writeU8(resultAddress, descriptor.fileType.code)
        memory.writeU16(resultAddress + 2, descriptor.flags)
        memory.writeU64(resultAddress + 8, descriptor.rightsBase)
        memory.writeU64(resultAddress + 16, descriptor.rightsInheriting)
        return WasiErrno.SUCCESS
    }

    private fun fdFdstatSetFlags(
        fd: Int,
        flags: Int,
    ): WasiErrno {
        if (flags and WasiFdFlags.ALL.inv() != 0) fail(WasiErrno.INVAL)
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_FDSTAT_SET_FLAGS)
        descriptor.flags = flags
        return WasiErrno.SUCCESS
    }

    private fun fdFdstatSetRights(
        fd: Int,
        rightsBase: ULong,
        rightsInheriting: ULong,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        if (rightsBase and descriptor.rightsBase.inv() != 0uL ||
            rightsInheriting and descriptor.rightsInheriting.inv() != 0uL
        ) {
            fail(WasiErrno.NOTCAPABLE)
        }
        descriptor.rightsBase = rightsBase
        descriptor.rightsInheriting = rightsInheriting
        return WasiErrno.SUCCESS
    }

    private suspend fun fdFilestatGet(
        memory: GuestMemory,
        fd: Int,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_FILESTAT_GET)
        memory.check(resultAddress, FILESTAT_SIZE)
        val stat =
            when (descriptor) {
                is FileDescriptor -> descriptor.file.stat()
                is DirectoryDescriptor -> descriptor.directory.statSelf()
                is InputDescriptor,
                is OutputDescriptor,
                -> WasiFileStat(size = 0u)
            }
        writeFileStat(memory, resultAddress, descriptor.fileType, stat)
        return WasiErrno.SUCCESS
    }

    private suspend fun fdFilestatSetSize(
        fd: Int,
        size: ULong,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_FILESTAT_SET_SIZE)
        descriptor.regularFile().file.setSize(signedFileSize(size, WasiErrno.FBIG))
        return WasiErrno.SUCCESS
    }

    private suspend fun fdFilestatSetTimes(
        fd: Int,
        accessTimeNanos: ULong,
        modificationTimeNanos: ULong,
        flags: Int,
    ): WasiErrno {
        if (flags and FILESTAT_SET_TIMES_FLAGS_ALL.inv() != 0 ||
            flags and FILESTAT_SET_ATIM != 0 && flags and FILESTAT_SET_ATIM_NOW != 0 ||
            flags and FILESTAT_SET_MTIM != 0 && flags and FILESTAT_SET_MTIM_NOW != 0
        ) {
            fail(WasiErrno.INVAL)
        }
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_FILESTAT_SET_TIMES)
        val needsNow =
            flags and (FILESTAT_SET_ATIM_NOW or FILESTAT_SET_MTIM_NOW) != 0
        val now =
            if (needsNow) {
                config.clock.timeNanos(CLOCK_REALTIME, 0u) ?: fail(WasiErrno.INVAL)
            } else {
                0u
            }
        val accessTime =
            when {
                flags and FILESTAT_SET_ATIM != 0 -> accessTimeNanos
                flags and FILESTAT_SET_ATIM_NOW != 0 -> now
                else -> null
            }
        val modificationTime =
            when {
                flags and FILESTAT_SET_MTIM != 0 -> modificationTimeNanos
                flags and FILESTAT_SET_MTIM_NOW != 0 -> now
                else -> null
            }
        when (descriptor) {
            is FileDescriptor ->
                descriptor.file.setTimes(accessTime, modificationTime)
            is DirectoryDescriptor ->
                descriptor.directory.setTimesSelf(accessTime, modificationTime)
            is InputDescriptor,
            is OutputDescriptor,
            -> fail(WasiErrno.SPIPE)
        }
        return WasiErrno.SUCCESS
    }

    private fun writeFileStat(
        memory: GuestMemory,
        resultAddress: Long,
        fileType: WasiFileType,
        stat: WasiFileStat,
    ) {
        memory.check(resultAddress, FILESTAT_SIZE)
        memory.zero(resultAddress, FILESTAT_SIZE)
        memory.writeU64(resultAddress, stat.deviceId)
        memory.writeU64(resultAddress + 8, stat.inode)
        memory.writeU8(resultAddress + 16, fileType.code)
        memory.writeU64(resultAddress + 24, stat.linkCount)
        memory.writeU64(resultAddress + 32, stat.size)
        memory.writeU64(resultAddress + 40, stat.accessTimeNanos)
        memory.writeU64(resultAddress + 48, stat.modificationTimeNanos)
        memory.writeU64(resultAddress + 56, stat.changeTimeNanos)
    }

    private fun fdPrestatGet(
        memory: GuestMemory,
        fd: Int,
        resultAddress: Long,
    ): WasiErrno {
        val directory = descriptor(fd) as? DirectoryDescriptor ?: fail(WasiErrno.BADF)
        val preopenName = directory.preopenName ?: fail(WasiErrno.BADF)
        memory.check(resultAddress, PRESTAT_SIZE)
        memory.zero(resultAddress, PRESTAT_SIZE)
        memory.writeU8(resultAddress, 0)
        memory.writeU32(resultAddress + 4, preopenName.size.toUInt())
        return WasiErrno.SUCCESS
    }

    private fun fdPrestatDirName(
        memory: GuestMemory,
        fd: Int,
        resultAddress: Long,
        requestedLength: Int,
    ): WasiErrno {
        val directory = descriptor(fd) as? DirectoryDescriptor ?: fail(WasiErrno.BADF)
        val preopenName = directory.preopenName ?: fail(WasiErrno.BADF)
        if (requestedLength < preopenName.size) fail(WasiErrno.NAMETOOLONG)
        memory.check(resultAddress, preopenName.size)
        memory.write(resultAddress, preopenName)
        return WasiErrno.SUCCESS
    }

    private suspend fun fdPread(
        memory: GuestMemory,
        fd: Int,
        iovecsAddress: Long,
        iovecCount: Int,
        offset: ULong,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_READ)
        descriptor.requireRight(WasiRights.FD_SEEK)
        val file = descriptor.regularFile()
        val iovecs = memory.readIovecs(iovecsAddress, iovecCount, config.maxIovecs)
        memory.check(resultAddress, 4)
        var currentOffset = signedFileSize(offset, WasiErrno.OVERFLOW)
        var total = 0L
        for (iovec in iovecs) {
            if (iovec.length == 0) continue
            val bytes = file.file.read(currentOffset, iovec.length)
            if (bytes.size > iovec.length) fail(WasiErrno.IO)
            memory.write(iovec.address, bytes)
            total = checkedAdd(total, bytes.size.toLong())
            currentOffset = checkedAdd(currentOffset, bytes.size.toLong())
            if (bytes.size < iovec.length) break
        }
        if (total > UInt.MAX_VALUE.toLong()) fail(WasiErrno.OVERFLOW)
        memory.writeU32(resultAddress, total.toUInt())
        return WasiErrno.SUCCESS
    }

    private suspend fun fdReaddir(
        memory: GuestMemory,
        fd: Int,
        bufferAddress: Long,
        bufferLength: Int,
        cookie: ULong,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd) as? DirectoryDescriptor
            ?: fail(WasiErrno.NOTDIR)
        descriptor.requireRight(WasiRights.FD_READDIR)
        memory.check(bufferAddress, bufferLength)
        memory.check(resultAddress, 4)

        if (bufferLength == 0) {
            memory.writeU32(resultAddress, 0u)
            return WasiErrno.SUCCESS
        }

        val children = descriptor.directory.readEntries()
            .onEach(::validateDirectoryEntry)
            .sortedBy { it.name }
        val selfInode = descriptor.directory.statSelf().inode
        val entries = buildList {
            add(WasiDirectoryEntry(".", WasiFileType.DIRECTORY, selfInode))
            add(WasiDirectoryEntry("..", WasiFileType.DIRECTORY))
            addAll(children)
        }
        if (cookie > Int.MAX_VALUE.toULong() || cookie >= entries.size.toULong()) {
            memory.writeU32(resultAddress, 0u)
            return WasiErrno.SUCCESS
        }

        val output = ByteArray(bufferLength)
        var used = 0
        var index = cookie.toInt()
        while (index < entries.size && used < output.size) {
            val record = encodeDirectoryEntry(
                entry = entries[index],
                nextCookie = (index + 1).toULong(),
            )
            val copied = minOf(record.size, output.size - used)
            record.copyInto(output, destinationOffset = used, endIndex = copied)
            used += copied
            if (copied < record.size) break
            index++
        }
        memory.write(bufferAddress, output.copyOf(used))
        memory.writeU32(resultAddress, used.toUInt())
        return WasiErrno.SUCCESS
    }

    private suspend fun fdPwrite(
        memory: GuestMemory,
        fd: Int,
        iovecsAddress: Long,
        iovecCount: Int,
        offset: ULong,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_WRITE)
        descriptor.requireRight(WasiRights.FD_SEEK)
        val file = descriptor.regularFile()
        val iovecs = memory.readIovecs(iovecsAddress, iovecCount, config.maxIovecs)
        memory.check(resultAddress, 4)
        var currentOffset = signedFileSize(offset, WasiErrno.OVERFLOW)
        var total = 0L
        for (iovec in iovecs) {
            if (iovec.length == 0) continue
            val bytes = memory.read(iovec.address, iovec.length)
            val written = file.file.write(currentOffset, bytes)
            if (written !in 0..bytes.size) fail(WasiErrno.IO)
            total = checkedAdd(total, written.toLong())
            currentOffset = checkedAdd(currentOffset, written.toLong())
            if (written < bytes.size) break
        }
        if (total > UInt.MAX_VALUE.toLong()) fail(WasiErrno.OVERFLOW)
        memory.writeU32(resultAddress, total.toUInt())
        return WasiErrno.SUCCESS
    }

    private suspend fun fdRead(
        memory: GuestMemory,
        fd: Int,
        iovecsAddress: Long,
        iovecCount: Int,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_READ)
        val iovecs = memory.readIovecs(iovecsAddress, iovecCount, config.maxIovecs)
        memory.check(resultAddress, 4)
        var total = 0L

        for (iovec in iovecs) {
            if (iovec.length == 0) continue
            val bytes =
                when (descriptor) {
                    is InputDescriptor -> descriptor.input.read(iovec.length).also {
                        if (it.size > iovec.length) fail(WasiErrno.IO)
                    }
                    is FileDescriptor -> descriptor.read(iovec.length)
                    is DirectoryDescriptor -> fail(WasiErrno.ISDIR)
                    is OutputDescriptor -> fail(WasiErrno.BADF)
                }
            memory.write(iovec.address, bytes)
            total = checkedAdd(total, bytes.size.toLong())
            if (bytes.size < iovec.length) break
        }
        if (total > UInt.MAX_VALUE.toLong()) fail(WasiErrno.OVERFLOW)
        memory.writeU32(resultAddress, total.toUInt())
        return WasiErrno.SUCCESS
    }

    private suspend fun fdRenumber(
        from: Int,
        to: Int,
    ): WasiErrno {
        val source = descriptor(from)
        val target = descriptor(to)
        if (from == to) return WasiErrno.SUCCESS
        target.close()
        descriptors.remove(from)
        descriptors[to] = source
        descriptorTableMutated = true
        return WasiErrno.SUCCESS
    }

    private suspend fun fdSeek(
        memory: GuestMemory,
        fd: Int,
        offset: Long,
        whence: Int,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_SEEK)
        val file = descriptor as? FileDescriptor
            ?: if (descriptor is DirectoryDescriptor) fail(WasiErrno.ISDIR)
            else fail(WasiErrno.SPIPE)
        memory.check(resultAddress, 8)
        val base =
            when (whence) {
                0 -> 0L
                1 -> file.position
                2 -> file.file.size()
                else -> fail(WasiErrno.INVAL)
            }
        val newPosition = checkedSignedAdd(base, offset)
        if (newPosition < 0) fail(WasiErrno.INVAL)
        file.position = newPosition
        memory.writeU64(resultAddress, newPosition.toULong())
        return WasiErrno.SUCCESS
    }

    private fun fdTell(
        memory: GuestMemory,
        fd: Int,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_TELL)
        val file = descriptor.regularFile()
        memory.writeU64(resultAddress, file.position.toULong())
        return WasiErrno.SUCCESS
    }

    private suspend fun fdWrite(
        memory: GuestMemory,
        fd: Int,
        iovecsAddress: Long,
        iovecCount: Int,
        resultAddress: Long,
    ): WasiErrno {
        val descriptor = descriptor(fd)
        descriptor.requireRight(WasiRights.FD_WRITE)
        val iovecs = memory.readIovecs(iovecsAddress, iovecCount, config.maxIovecs)
        memory.check(resultAddress, 4)
        var total = 0L

        for (iovec in iovecs) {
            if (iovec.length == 0) continue
            val bytes = memory.read(iovec.address, iovec.length)
            val written =
                when (descriptor) {
                    is OutputDescriptor -> descriptor.output.write(bytes)
                    is FileDescriptor -> descriptor.write(bytes)
                    is DirectoryDescriptor -> fail(WasiErrno.ISDIR)
                    is InputDescriptor -> fail(WasiErrno.BADF)
                }
            if (written !in 0..bytes.size) fail(WasiErrno.IO)
            total = checkedAdd(total, written.toLong())
            if (written < bytes.size) break
        }
        if (total > UInt.MAX_VALUE.toLong()) fail(WasiErrno.OVERFLOW)
        memory.writeU32(resultAddress, total.toUInt())
        return WasiErrno.SUCCESS
    }

    private suspend fun pathCreateDirectory(
        memory: GuestMemory,
        directoryFd: Int,
        pathAddress: Long,
        pathLength: Int,
    ): WasiErrno {
        val path = readGuestPath(memory, pathAddress, pathLength)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_CREATE_DIRECTORY)
        directory.directory.createDirectory(path)
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathFilestatGet(
        memory: GuestMemory,
        directoryFd: Int,
        lookupFlags: Int,
        pathAddress: Long,
        pathLength: Int,
        resultAddress: Long,
    ): WasiErrno {
        validateLookupFlags(lookupFlags)
        val path = readGuestPath(memory, pathAddress, pathLength)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_FILESTAT_GET)
        memory.check(resultAddress, FILESTAT_SIZE)
        val stat = directory.directory.stat(
            path = path,
            followSymlinks = lookupFlags and LOOKUP_FLAGS_SYMLINK_FOLLOW != 0,
        )
        writeFileStat(memory, resultAddress, stat.fileType, stat.attributes)
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathFilestatSetTimes(
        memory: GuestMemory,
        directoryFd: Int,
        lookupFlags: Int,
        pathAddress: Long,
        pathLength: Int,
        accessTimeNanos: ULong,
        modificationTimeNanos: ULong,
        flags: Int,
    ): WasiErrno {
        validateLookupFlags(lookupFlags)
        validateFileStatSetTimesFlags(flags)
        val path = readGuestPath(memory, pathAddress, pathLength)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_FILESTAT_SET_TIMES)
        val now = currentRealtimeForFileTimes(flags)
        directory.directory.setTimes(
            path = path,
            followSymlinks = lookupFlags and LOOKUP_FLAGS_SYMLINK_FOLLOW != 0,
            accessTimeNanos =
                when {
                    flags and FILESTAT_SET_ATIM != 0 -> accessTimeNanos
                    flags and FILESTAT_SET_ATIM_NOW != 0 -> now
                    else -> null
                },
            modificationTimeNanos =
                when {
                    flags and FILESTAT_SET_MTIM != 0 -> modificationTimeNanos
                    flags and FILESTAT_SET_MTIM_NOW != 0 -> now
                    else -> null
                },
        )
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathLink(
        memory: GuestMemory,
        sourceDirectoryFd: Int,
        sourceLookupFlags: Int,
        sourcePathAddress: Long,
        sourcePathLength: Int,
        targetDirectoryFd: Int,
        targetPathAddress: Long,
        targetPathLength: Int,
    ): WasiErrno {
        validateLookupFlags(sourceLookupFlags)
        val sourcePath = readGuestPath(memory, sourcePathAddress, sourcePathLength)
        val targetPath = readGuestPath(memory, targetPathAddress, targetPathLength)
        val source = directoryDescriptor(sourceDirectoryFd)
        val target = directoryDescriptor(targetDirectoryFd)
        source.requireRight(WasiRights.PATH_LINK_SOURCE)
        target.requireRight(WasiRights.PATH_LINK_TARGET)
        source.directory.link(
            sourcePath = sourcePath,
            followSourceSymlink =
                sourceLookupFlags and LOOKUP_FLAGS_SYMLINK_FOLLOW != 0,
            targetDirectory = target.directory,
            targetPath = targetPath,
        )
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathReadLink(
        memory: GuestMemory,
        directoryFd: Int,
        pathAddress: Long,
        pathLength: Int,
        bufferAddress: Long,
        bufferLength: Int,
        resultAddress: Long,
    ): WasiErrno {
        val path = readGuestPath(memory, pathAddress, pathLength)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_READLINK)
        memory.check(bufferAddress, bufferLength)
        memory.check(resultAddress, 4)
        val target = directory.directory.readLink(path)
        val copied = minOf(bufferLength, target.size)
        memory.write(bufferAddress, target.copyOf(copied))
        memory.writeU32(resultAddress, copied.toUInt())
        return WasiErrno.SUCCESS
    }

    private suspend fun pathRemoveDirectory(
        memory: GuestMemory,
        directoryFd: Int,
        pathAddress: Long,
        pathLength: Int,
    ): WasiErrno {
        val path = readGuestPath(memory, pathAddress, pathLength)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_REMOVE_DIRECTORY)
        directory.directory.removeDirectory(path)
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathRename(
        memory: GuestMemory,
        sourceDirectoryFd: Int,
        sourcePathAddress: Long,
        sourcePathLength: Int,
        targetDirectoryFd: Int,
        targetPathAddress: Long,
        targetPathLength: Int,
    ): WasiErrno {
        val sourcePath = readGuestPath(memory, sourcePathAddress, sourcePathLength)
        val targetPath = readGuestPath(memory, targetPathAddress, targetPathLength)
        val source = directoryDescriptor(sourceDirectoryFd)
        val target = directoryDescriptor(targetDirectoryFd)
        source.requireRight(WasiRights.PATH_RENAME_SOURCE)
        target.requireRight(WasiRights.PATH_RENAME_TARGET)
        source.directory.rename(
            sourcePath = sourcePath,
            targetDirectory = target.directory,
            targetPath = targetPath,
        )
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathSymbolicLink(
        memory: GuestMemory,
        targetPathAddress: Long,
        targetPathLength: Int,
        directoryFd: Int,
        linkPathAddress: Long,
        linkPathLength: Int,
    ): WasiErrno {
        val targetPath = readGuestPath(memory, targetPathAddress, targetPathLength)
        val linkPath = readGuestPath(memory, linkPathAddress, linkPathLength)
        requireRelativeSymlinkTarget(targetPath)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_SYMLINK)
        directory.directory.createSymbolicLink(
            targetPath = targetPath,
            linkPath = linkPath,
        )
        return WasiErrno.SUCCESS
    }

    private suspend fun pathUnlinkFile(
        memory: GuestMemory,
        directoryFd: Int,
        pathAddress: Long,
        pathLength: Int,
    ): WasiErrno {
        val path = readGuestPath(memory, pathAddress, pathLength)
        val directory = directoryDescriptor(directoryFd)
        directory.requireRight(WasiRights.PATH_UNLINK_FILE)
        directory.directory.unlinkFile(path)
        return WasiErrno.SUCCESS
    }

    @Suppress("LongParameterList")
    private suspend fun pathOpen(
        memory: GuestMemory,
        directoryFd: Int,
        lookupFlags: Int,
        pathAddress: Long,
        pathLength: Int,
        openFlags: Int,
        rightsBase: ULong,
        rightsInheriting: ULong,
        fdFlags: Int,
        resultAddress: Long,
    ): WasiErrno {
        if (lookupFlags and LOOKUP_FLAGS_ALL.inv() != 0 ||
            openFlags and WasiOpenFlags.ALL.inv() != 0 ||
            fdFlags and WasiFdFlags.ALL.inv() != 0
        ) {
            fail(WasiErrno.INVAL)
        }
        memory.check(resultAddress, 4)
        val path = readGuestPath(memory, pathAddress, pathLength)

        val directory = descriptor(directoryFd) as? DirectoryDescriptor
            ?: fail(WasiErrno.NOTDIR)
        directory.requireRight(WasiRights.PATH_OPEN)
        if (rightsBase and directory.rightsInheriting != rightsBase ||
            rightsInheriting and directory.rightsInheriting != rightsInheriting
        ) {
            fail(WasiErrno.NOTCAPABLE)
        }

        val create = openFlags and WasiOpenFlags.CREATE != 0
        val truncate = openFlags and WasiOpenFlags.TRUNCATE != 0
        if (create) directory.requireRight(WasiRights.PATH_CREATE_FILE)
        if (truncate) directory.requireRight(WasiRights.PATH_FILESTAT_SET_SIZE)
        if (openFlags and WasiOpenFlags.EXCLUSIVE != 0 && !create) fail(WasiErrno.INVAL)

        val opened = directory.directory.open(
            path = path,
            options = WasiPathOpenOptions(
                create = create,
                exclusive = openFlags and WasiOpenFlags.EXCLUSIVE != 0,
                truncate = truncate,
                requireDirectory =
                    openFlags and WasiOpenFlags.DIRECTORY != 0 || path.endsWith('/'),
                read = rightsBase and WasiRights.FD_READ != 0uL,
                write = rightsBase and FILE_MUTATION_RIGHTS != 0uL,
                followSymlinks = lookupFlags and LOOKUP_FLAGS_SYMLINK_FOLLOW != 0,
            ),
        )
        val descriptor =
            when (opened) {
                is WasiOpenedFile -> FileDescriptor(
                    file = opened.file,
                    rightsBase = rightsBase and WasiRights.REGULAR_FILE_DEFAULT,
                    rightsInheriting = 0u,
                    flags = fdFlags,
                )
                is WasiOpenedDirectory -> {
                    if (rightsBase and FILE_MUTATION_RIGHTS != 0uL) {
                        fail(WasiErrno.ISDIR)
                    }
                    DirectoryDescriptor(
                        directory = opened.directory,
                        preopenName = null,
                        rightsBase = rightsBase and WasiRights.DIRECTORY_DEFAULT,
                        rightsInheriting = rightsInheriting and
                            (WasiRights.DIRECTORY_DEFAULT or WasiRights.REGULAR_FILE_DEFAULT),
                        flags = fdFlags,
                    )
                }
            }
        val newFd = allocateFd()
        descriptors[newFd] = descriptor
        descriptorTableMutated = true
        memory.writeU32(resultAddress, newFd.toUInt())
        return WasiErrno.SUCCESS
    }

    private fun readGuestPath(
        memory: GuestMemory,
        pathAddress: Long,
        pathLength: Int,
    ): String {
        if (pathLength > config.maxPathBytes) fail(WasiErrno.NAMETOOLONG)
        val pathBytes = memory.read(pathAddress, pathLength)
        return try {
            pathBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            fail(WasiErrno.INVAL)
        }
    }

    private fun directoryDescriptor(fd: Int): DirectoryDescriptor =
        descriptor(fd) as? DirectoryDescriptor ?: fail(WasiErrno.NOTDIR)

    private fun validateLookupFlags(flags: Int) {
        if (flags and LOOKUP_FLAGS_ALL.inv() != 0) fail(WasiErrno.INVAL)
    }

    private fun validateFileStatSetTimesFlags(flags: Int) {
        if (flags and FILESTAT_SET_TIMES_FLAGS_ALL.inv() != 0 ||
            flags and FILESTAT_SET_ATIM != 0 && flags and FILESTAT_SET_ATIM_NOW != 0 ||
            flags and FILESTAT_SET_MTIM != 0 && flags and FILESTAT_SET_MTIM_NOW != 0
        ) {
            fail(WasiErrno.INVAL)
        }
    }

    private fun currentRealtimeForFileTimes(flags: Int): ULong? =
        if (flags and (FILESTAT_SET_ATIM_NOW or FILESTAT_SET_MTIM_NOW) != 0) {
            config.clock.timeNanos(CLOCK_REALTIME, 0u) ?: fail(WasiErrno.INVAL)
        } else {
            null
        }

    private suspend fun pollOneoff(
        memory: GuestMemory,
        subscriptionsAddress: Long,
        eventsAddress: Long,
        subscriptionCount: Int,
        resultAddress: Long,
    ): WasiErrno {
        if (subscriptionCount == 0) fail(WasiErrno.INVAL)
        val subscriptionsBytes = checkedProduct(subscriptionCount, SUBSCRIPTION_SIZE)
        val eventsBytes = checkedProduct(subscriptionCount, EVENT_SIZE)
        memory.check(subscriptionsAddress, subscriptionsBytes)
        memory.check(eventsAddress, eventsBytes)
        memory.check(resultAddress, 4)

        val subscriptions = List(subscriptionCount) { index ->
            readSubscription(memory, subscriptionsAddress + index * SUBSCRIPTION_SIZE.toLong())
        }
        val candidates = buildList {
            for (subscription in subscriptions) {
                add(
                    when (subscription) {
                        is PollSubscription.Clock -> clockPollCandidate(subscription)
                        is PollSubscription.FileDescriptor ->
                            fileDescriptorPollCandidate(subscription)
                    },
                )
            }
        }
        val readyAfterNanos = candidates.minOf { it.readyAfterNanos }
        delayForNanos(readyAfterNanos)
        val events = candidates
            .filter { it.readyAfterNanos <= readyAfterNanos }
            .map { it.event }

        events.forEachIndexed { index, event ->
            writeEvent(memory, eventsAddress + index * EVENT_SIZE.toLong(), event)
        }
        memory.writeU32(resultAddress, events.size.toUInt())
        return WasiErrno.SUCCESS
    }

    private fun readSubscription(
        memory: GuestMemory,
        address: Long,
    ): PollSubscription {
        val userdata = memory.readU64(address)
        return when (val type = memory.readU8(address + 8)) {
            EVENTTYPE_CLOCK -> {
                val flags = memory.readU16(address + 40)
                if (flags and SUBSCRIPTION_CLOCK_FLAGS_ALL.inv() != 0) {
                    fail(WasiErrno.INVAL)
                }
                PollSubscription.Clock(
                    userdata = userdata,
                    clockId = memory.readU32(address + 16).toInt(),
                    timeoutNanos = memory.readU64(address + 24),
                    precisionNanos = memory.readU64(address + 32),
                    absolute = flags and SUBSCRIPTION_CLOCK_ABSTIME != 0,
                )
            }
            EVENTTYPE_FD_READ,
            EVENTTYPE_FD_WRITE,
            -> PollSubscription.FileDescriptor(
                userdata = userdata,
                type = type,
                fd = memory.readU32(address + 16).toInt(),
            )
            else -> fail(WasiErrno.INVAL)
        }
    }

    private fun clockPollCandidate(
        subscription: PollSubscription.Clock,
    ): PollCandidate {
        val now = config.clock.timeNanos(
            subscription.clockId,
            subscription.precisionNanos,
        ) ?: fail(WasiErrno.INVAL)
        val remaining =
            if (!subscription.absolute) {
                subscription.timeoutNanos
            } else if (subscription.timeoutNanos > now) {
                subscription.timeoutNanos - now
            } else {
                0u
            }
        return PollCandidate(
            readyAfterNanos = remaining,
            event = PollEvent(
                userdata = subscription.userdata,
                error = WasiErrno.SUCCESS,
                type = EVENTTYPE_CLOCK,
            ),
        )
    }

    private suspend fun fileDescriptorPollCandidate(
        subscription: PollSubscription.FileDescriptor,
    ): PollCandidate {
        val descriptor = descriptors[subscription.fd]
        val requiredDirectionRight =
            if (subscription.type == EVENTTYPE_FD_READ) {
                WasiRights.FD_READ
            } else {
                WasiRights.FD_WRITE
            }
        val error =
            when {
                descriptor == null -> WasiErrno.BADF
                descriptor.rightsBase and WasiRights.POLL_FD_READWRITE == 0uL ||
                    descriptor.rightsBase and requiredDirectionRight == 0uL ->
                    WasiErrno.NOTCAPABLE
                else -> WasiErrno.SUCCESS
            }
        val readableBytes =
            if (error == WasiErrno.SUCCESS &&
                subscription.type == EVENTTYPE_FD_READ &&
                descriptor is FileDescriptor
            ) {
                (descriptor.file.size() - descriptor.position)
                    .coerceAtLeast(0)
                    .toULong()
            } else {
                0u
            }
        return PollCandidate(
            readyAfterNanos = 0u,
            event = PollEvent(
                userdata = subscription.userdata,
                error = error,
                type = subscription.type,
                readableBytes = readableBytes,
            ),
        )
    }

    private fun writeEvent(
        memory: GuestMemory,
        address: Long,
        event: PollEvent,
    ) {
        memory.zero(address, EVENT_SIZE)
        memory.writeU64(address, event.userdata)
        memory.writeU16(address + 8, event.error.code)
        memory.writeU8(address + 10, event.type)
        if (event.type == EVENTTYPE_FD_READ || event.type == EVENTTYPE_FD_WRITE) {
            memory.writeU64(address + 16, event.readableBytes)
            memory.writeU16(address + 24, event.flags)
        }
    }

    private fun sockShutdown(fd: Int): WasiErrno {
        descriptor(fd)
        fail(WasiErrno.NOTSOCK)
    }

    private suspend fun randomGet(
        memory: GuestMemory,
        address: Long,
        length: Int,
    ): WasiErrno {
        memory.check(address, length)
        val bytes = ByteArray(length)
        try {
            config.random.fill(bytes)
        } catch (_: WasiRandomException) {
            fail(WasiErrno.IO)
        }
        memory.write(address, bytes)
        return WasiErrno.SUCCESS
    }

    private fun descriptor(fd: Int): Descriptor =
        descriptors[fd] ?: fail(WasiErrno.BADF)

    private fun requireAttachedInstance(instance: Instance) {
        if (attachedInstance !== instance) {
            throw SnapshotStateException(
                "WASI descriptor state is attached to a different instance",
            )
        }
    }

    private fun allocateFd(): Int {
        var fd = FIRST_PREOPEN_FD
        while (fd >= 0 && descriptors.containsKey(fd)) fd++
        if (fd < 0) fail(WasiErrno.MFILE)
        return fd
    }

    private fun captureDescriptorState(hooks: HostSnapshotHooks?): ByteArray {
        if (descriptors.isEmpty()) {
            return WasiSnapshotStateCodec.encode(emptyList())
        }
        val resourceHooks = hooks as? WasiSnapshotResourceHooks
            ?: throw SnapshotStateException(
                "WASI descriptor state for open descriptors " +
                    descriptors.keys.sorted().joinToString { "fd $it" } +
                    " requires SnapshotHooks that also implement " +
                    "WasiSnapshotResourceHooks",
            )
        val missing = mutableListOf<String>()
        val snapshots = descriptors.entries
            .sortedBy { it.key }
            .mapNotNull { (fd, descriptor) ->
                val key =
                    when (descriptor) {
                        is InputDescriptor ->
                            resourceHooks.externalizeInput(fd, descriptor.input)
                        is OutputDescriptor ->
                            resourceHooks.externalizeOutput(fd, descriptor.output)
                        is DirectoryDescriptor ->
                            resourceHooks.externalizeDirectory(fd, descriptor.directory)
                        is FileDescriptor ->
                            resourceHooks.externalizeFile(fd, descriptor.file)
                    }
                if (key == null) {
                    missing += "fd $fd (${descriptor.snapshotKind.displayName})"
                    null
                } else {
                    descriptor.toSnapshot(fd, key)
                }
            }
        if (missing.isNotEmpty()) {
            throw SnapshotStateException(
                "WASI resources were declined by WasiSnapshotResourceHooks: " +
                    missing.joinToString(),
            )
        }
        return WasiSnapshotStateCodec.encode(snapshots)
    }

    private fun prepareDescriptorRestore(
        payload: ByteArray,
        hooks: HostSnapshotHooks?,
    ): HostSnapshotRestore {
        if (descriptorTableMutated) {
            throw SnapshotStateException(
                "target WASI descriptor table is not pristine; restore into a newly " +
                    "created WasiPreview1 to avoid leaking or replacing live resources",
            )
        }
        val snapshots = WasiSnapshotStateCodec.decode(payload)
        if (snapshots.isEmpty()) {
            return HostSnapshotRestore {
                descriptors.clear()
                descriptorTableMutated = true
            }
        }
        val resourceHooks = hooks as? WasiSnapshotResourceHooks
            ?: throw SnapshotStateException(
                "restoring WASI descriptor state requires SnapshotHooks that also " +
                    "implement WasiSnapshotResourceHooks",
            )
        val missing = mutableListOf<String>()
        val restored = linkedMapOf<Int, Descriptor>()
        snapshots.forEach { snapshot ->
            val descriptor: Descriptor? =
                when (snapshot.kind) {
                    WasiSnapshotDescriptorKind.Input ->
                        resourceHooks.rehydrateInput(
                            snapshot.fd,
                            snapshot.resourceKey.copyOf(),
                        )?.let { input ->
                            InputDescriptor(
                                input = input,
                                rightsBase = snapshot.rightsBase,
                                flags = snapshot.flags,
                            )
                        }
                    WasiSnapshotDescriptorKind.Output ->
                        resourceHooks.rehydrateOutput(
                            snapshot.fd,
                            snapshot.resourceKey.copyOf(),
                        )?.let { output ->
                            OutputDescriptor(
                                output = output,
                                rightsBase = snapshot.rightsBase,
                                flags = snapshot.flags,
                            )
                        }
                    WasiSnapshotDescriptorKind.Directory ->
                        resourceHooks.rehydrateDirectory(
                            snapshot.fd,
                            snapshot.resourceKey.copyOf(),
                        )?.let { directory ->
                            DirectoryDescriptor(
                                directory = directory,
                                preopenName = snapshot.preopenName?.copyOf(),
                                rightsBase = snapshot.rightsBase,
                                rightsInheriting = snapshot.rightsInheriting,
                                flags = snapshot.flags,
                            )
                        }
                    WasiSnapshotDescriptorKind.File ->
                        resourceHooks.rehydrateFile(
                            snapshot.fd,
                            snapshot.resourceKey.copyOf(),
                        )?.let { file ->
                            FileDescriptor(
                                file = file,
                                rightsBase = snapshot.rightsBase,
                                rightsInheriting = snapshot.rightsInheriting,
                                flags = snapshot.flags,
                            ).also { it.position = snapshot.position }
                        }
                }
            if (descriptor == null) {
                missing += "fd ${snapshot.fd} (${snapshot.kind.displayName})"
            } else {
                restored[snapshot.fd] = descriptor
            }
        }
        if (missing.isNotEmpty()) {
            throw SnapshotStateException(
                "WASI resources could not be rehydrated by WasiSnapshotResourceHooks: " +
                    missing.joinToString(),
            )
        }
        return HostSnapshotRestore {
            descriptors.clear()
            descriptors.putAll(restored)
            descriptorTableMutated = true
        }
    }

    private companion object {
        const val WASI_SNAPSHOT_PARTICIPANT_ID =
            "io.heapy.kwasm.wasi.preview1-descriptors"
        const val FIRST_PREOPEN_FD = 3
        const val CLOCK_REALTIME = 0
        const val ADVICE_NORMAL = 0
        const val ADVICE_NOREUSE = 5
        const val FDSTAT_SIZE = 24
        const val FILESTAT_SIZE = 64
        const val PRESTAT_SIZE = 8
        const val SUBSCRIPTION_SIZE = 48
        const val EVENT_SIZE = 32
        const val EVENTTYPE_CLOCK = 0
        const val EVENTTYPE_FD_READ = 1
        const val EVENTTYPE_FD_WRITE = 2
        const val SUBSCRIPTION_CLOCK_ABSTIME = 1
        const val SUBSCRIPTION_CLOCK_FLAGS_ALL = SUBSCRIPTION_CLOCK_ABSTIME
        const val FILESTAT_SET_ATIM = 1
        const val FILESTAT_SET_ATIM_NOW = 2
        const val FILESTAT_SET_MTIM = 4
        const val FILESTAT_SET_MTIM_NOW = 8
        const val FILESTAT_SET_TIMES_FLAGS_ALL =
            FILESTAT_SET_ATIM or FILESTAT_SET_ATIM_NOW or
                FILESTAT_SET_MTIM or FILESTAT_SET_MTIM_NOW
        val FILE_MUTATION_RIGHTS: ULong =
            WasiRights.FD_WRITE or WasiRights.FD_ALLOCATE or
                WasiRights.FD_FILESTAT_SET_SIZE
        const val LOOKUP_FLAGS_SYMLINK_FOLLOW = 1
        const val LOOKUP_FLAGS_ALL = LOOKUP_FLAGS_SYMLINK_FOLLOW
        val i32: ValType = ValType.I32
        val i64: ValType = ValType.I64
    }
}

private fun validateDirectoryEntry(entry: WasiDirectoryEntry) {
    if (entry.name.isEmpty() ||
        entry.name == "." ||
        entry.name == ".." ||
        '/' in entry.name ||
        '\u0000' in entry.name
    ) {
        fail(WasiErrno.IO)
    }
}

private fun encodeDirectoryEntry(
    entry: WasiDirectoryEntry,
    nextCookie: ULong,
): ByteArray {
    val name = entry.name.encodeToByteArray()
    if (name.size > Int.MAX_VALUE - DIRENT_SIZE) fail(WasiErrno.OVERFLOW)
    return ByteArray(DIRENT_SIZE + name.size).also { record ->
        record.writeU64(0, nextCookie)
        record.writeU64(8, entry.inode)
        record.writeU32(16, name.size.toUInt())
        record[20] = entry.fileType.code.toByte()
        name.copyInto(record, destinationOffset = DIRENT_SIZE)
    }
}

private sealed interface PollSubscription {
    val userdata: ULong

    data class Clock(
        override val userdata: ULong,
        val clockId: Int,
        val timeoutNanos: ULong,
        val precisionNanos: ULong,
        val absolute: Boolean,
    ) : PollSubscription

    data class FileDescriptor(
        override val userdata: ULong,
        val type: Int,
        val fd: Int,
    ) : PollSubscription
}

private data class PollCandidate(
    val readyAfterNanos: ULong,
    val event: PollEvent,
)

private data class PollEvent(
    val userdata: ULong,
    val error: WasiErrno,
    val type: Int,
    val readableBytes: ULong = 0u,
    val flags: Int = 0,
)

private suspend fun delayForNanos(nanos: ULong) {
    var milliseconds = nanos / NANOS_PER_MILLISECOND
    if (nanos % NANOS_PER_MILLISECOND != 0uL) milliseconds++
    while (milliseconds > 0uL) {
        val chunk = minOf(milliseconds, Long.MAX_VALUE.toULong())
        delay(chunk.toLong())
        milliseconds -= chunk
    }
}

private fun ByteArray.writeU32(
    offset: Int,
    value: UInt,
) {
    repeat(4) { index ->
        this[offset + index] = (value shr (index * 8)).toByte()
    }
}

private fun ByteArray.writeU64(
    offset: Int,
    value: ULong,
) {
    repeat(8) { index ->
        this[offset + index] = (value shr (index * 8)).toByte()
    }
}

private sealed class Descriptor(
    val fileType: WasiFileType,
    var rightsBase: ULong,
    var rightsInheriting: ULong = 0u,
    var flags: Int = 0,
) {
    fun requireRight(right: ULong) {
        if (rightsBase and right != right) fail(WasiErrno.NOTCAPABLE)
    }

    open suspend fun close() = Unit

    abstract val snapshotKind: WasiSnapshotDescriptorKind

    fun toSnapshot(fd: Int, resourceKey: ByteArray): WasiSnapshotDescriptor =
        WasiSnapshotDescriptor(
            fd = fd,
            kind = snapshotKind,
            rightsBase = rightsBase,
            rightsInheriting = rightsInheriting,
            flags = flags,
            position = (this as? FileDescriptor)?.position ?: 0L,
            preopenName = (this as? DirectoryDescriptor)?.preopenName?.copyOf(),
            resourceKey = resourceKey.copyOf(),
        )
}

private fun Descriptor.regularFile(): FileDescriptor =
    this as? FileDescriptor
        ?: if (this is DirectoryDescriptor) fail(WasiErrno.ISDIR)
        else fail(WasiErrno.SPIPE)

private class InputDescriptor(
    val input: WasiInput,
    rightsBase: ULong = STANDARD_INPUT_RIGHTS,
    flags: Int = 0,
) : Descriptor(
    fileType = WasiFileType.CHARACTER_DEVICE,
    rightsBase = rightsBase,
    flags = flags,
) {
    override val snapshotKind: WasiSnapshotDescriptorKind =
        WasiSnapshotDescriptorKind.Input
}

private class OutputDescriptor(
    val output: WasiOutput,
    rightsBase: ULong = STANDARD_OUTPUT_RIGHTS,
    flags: Int = 0,
) : Descriptor(
    fileType = WasiFileType.CHARACTER_DEVICE,
    rightsBase = rightsBase,
    flags = flags,
) {
    override val snapshotKind: WasiSnapshotDescriptorKind =
        WasiSnapshotDescriptorKind.Output
}

private class FileDescriptor(
    val file: WasiFileHandle,
    rightsBase: ULong,
    rightsInheriting: ULong,
    flags: Int,
) : Descriptor(
    fileType = WasiFileType.REGULAR_FILE,
    rightsBase = rightsBase,
    rightsInheriting = rightsInheriting,
    flags = flags,
) {
    override val snapshotKind: WasiSnapshotDescriptorKind =
        WasiSnapshotDescriptorKind.File

    var position: Long = 0

    suspend fun read(maximumBytes: Int): ByteArray {
        return file.read(position, maximumBytes).also { bytes ->
            if (bytes.size > maximumBytes) fail(WasiErrno.IO)
            position = checkedAdd(position, bytes.size.toLong())
        }
    }

    suspend fun write(bytes: ByteArray): Int {
        val start =
            if (flags and WasiFdFlags.APPEND != 0) file.size()
            else position
        val written = file.write(start, bytes)
        if (written !in 0..bytes.size) fail(WasiErrno.IO)
        position = checkedAdd(start, written.toLong())
        return written
    }

    override suspend fun close() {
        file.close()
    }
}

private class DirectoryDescriptor(
    val directory: WasiDirectory,
    val preopenName: ByteArray?,
    rightsBase: ULong,
    rightsInheriting: ULong,
    flags: Int = 0,
) : Descriptor(
    fileType = WasiFileType.DIRECTORY,
    rightsBase = rightsBase,
    rightsInheriting = rightsInheriting,
    flags = flags,
) {
    override val snapshotKind: WasiSnapshotDescriptorKind =
        WasiSnapshotDescriptorKind.Directory
}

private data class GuestIovec(
    val address: Long,
    val length: Int,
)

private class GuestMemory(
    private val memory: MemoryInstance,
) {
    fun check(address: Long, length: Int) {
        if (length < 0) fail(WasiErrno.OVERFLOW)
        memory.checkRange(address, length)
    }

    fun read(address: Long, length: Int): ByteArray {
        check(address, length)
        return memory.load(address, length)
    }

    fun write(address: Long, bytes: ByteArray) {
        check(address, bytes.size)
        memory.store(address, bytes)
    }

    fun zero(address: Long, length: Int) {
        write(address, ByteArray(length))
    }

    fun writeU8(address: Long, value: Int) {
        memory.storeByte(address, value)
    }

    fun writeU16(address: Long, value: Int) {
        write(
            address,
            byteArrayOf(
                value.toByte(),
                (value ushr 8).toByte(),
            ),
        )
    }

    fun writeU32(address: Long, value: UInt) {
        write(
            address,
            byteArrayOf(
                value.toByte(),
                (value shr 8).toByte(),
                (value shr 16).toByte(),
                (value shr 24).toByte(),
            ),
        )
    }

    fun writeU64(address: Long, value: ULong) {
        write(
            address,
            ByteArray(8) { index -> (value shr (index * 8)).toByte() },
        )
    }

    fun readU8(address: Long): Int =
        memory.loadByte(address)

    fun readU16(address: Long): Int {
        val bytes = read(address, 2)
        return bytes[0].toUByte().toInt() or
            (bytes[1].toUByte().toInt() shl 8)
    }

    fun readU32(address: Long): UInt {
        val bytes = read(address, 4)
        return (bytes[0].toUByte().toUInt()) or
            (bytes[1].toUByte().toUInt() shl 8) or
            (bytes[2].toUByte().toUInt() shl 16) or
            (bytes[3].toUByte().toUInt() shl 24)
    }

    fun readU64(address: Long): ULong {
        val bytes = read(address, 8)
        var value = 0uL
        bytes.forEachIndexed { index, byte ->
            value = value or (byte.toUByte().toULong() shl (index * 8))
        }
        return value
    }

    fun readIovecs(
        address: Long,
        count: Int,
        maximumCount: Int,
    ): List<GuestIovec> {
        if (count > maximumCount) fail(WasiErrno.INVAL)
        val tableBytes = checkedProduct(count, 8)
        check(address, tableBytes)
        return List(count) { index ->
            val entryAddress = address + index * 8L
            val bufferAddress = readU32(entryAddress).toLong()
            val length = readU32(entryAddress + 4)
            if (length > Int.MAX_VALUE.toUInt()) fail(WasiErrno.OVERFLOW)
            GuestIovec(bufferAddress, length.toInt()).also {
                check(it.address, it.length)
            }
        }
    }
}

private fun i32(arguments: List<Value>, index: Int): Int =
    (arguments[index] as Value.I32).v

private fun i64(arguments: List<Value>, index: Int): Long =
    (arguments[index] as Value.I64).v

private fun ptr(arguments: List<Value>, index: Int): Long =
    i32(arguments, index).toUInt().toLong()

private fun size(arguments: List<Value>, index: Int): Int {
    val value = i32(arguments, index).toUInt()
    if (value > Int.MAX_VALUE.toUInt()) fail(WasiErrno.OVERFLOW)
    return value.toInt()
}

private fun signedFileSize(
    value: ULong,
    overflowErrno: WasiErrno,
): Long {
    if (value > Long.MAX_VALUE.toULong()) fail(overflowErrno)
    return value.toLong()
}

private fun checkedProduct(left: Int, right: Int): Int {
    if (left < 0 || right < 0 || left > Int.MAX_VALUE / right) fail(WasiErrno.OVERFLOW)
    return left * right
}

private fun checkedAdd(left: Long, right: Long): Long {
    if (right > 0 && left > Long.MAX_VALUE - right) fail(WasiErrno.OVERFLOW)
    if (right < 0 && left < Long.MIN_VALUE - right) fail(WasiErrno.OVERFLOW)
    return left + right
}

private fun checkedSignedAdd(left: Long, right: Long): Long =
    checkedAdd(left, right)

private fun fail(errno: WasiErrno): Nothing = throw WasiFileSystemException(errno)

private const val DIRENT_SIZE: Int = 24
private const val NANOS_PER_MILLISECOND: ULong = 1_000_000uL
