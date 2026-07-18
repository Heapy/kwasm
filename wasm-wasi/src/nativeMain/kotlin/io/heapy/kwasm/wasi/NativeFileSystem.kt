@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kwasm.wasi

import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_close
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_directory_read_batch
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_errno
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_datasync
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_kind
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_set_times
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_size
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_stat
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_sync
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_fd_truncate
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_open_directory_at
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_open_file_at
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_open_root
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_create_directory
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_link
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_readlink
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_remove_directory
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_rename
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_set_times
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_stat
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_symlink
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_path_unlink_file
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_pread
import io.heapy.kwasm.wasi.nativeinterop.kwasm_wasi_pwrite
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.EACCES
import platform.posix.EBADF
import platform.posix.EEXIST
import platform.posix.EFBIG
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.EIO
import platform.posix.EISDIR
import platform.posix.ELOOP
import platform.posix.EMFILE
import platform.posix.ENAMETOOLONG
import platform.posix.ENOENT
import platform.posix.ENOMEM
import platform.posix.ENOSYS
import platform.posix.ENOTDIR
import platform.posix.ENOTEMPTY
import platform.posix.ENOTSUP
import platform.posix.EOVERFLOW
import platform.posix.EPERM
import platform.posix.EROFS

/**
 * A POSIX filesystem capability for Kotlin/Native, confined to [preopenRoot].
 *
 * Every guest component is resolved with `openat(2)` from an already-open
 * directory descriptor. Absolute paths, `..`, and NUL are rejected before
 * reaching the host. Symlinks are deliberately rejected even when WASI asks
 * to follow them: this fail-closed subset prevents both escape and
 * time-of-check/time-of-use races on platforms without a portable
 * `RESOLVE_BENEATH` equivalent.
 *
 * Call [close] after the owning WASI instance is no longer used. Closing this
 * capability invalidates child directory capabilities obtained from it;
 * already-open regular files remain independently usable until their WASI
 * descriptors are closed.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class NativeFileSystem(
    /** Host directory granted to the guest. */
    public val preopenRoot: String,
) : WasiDirectory {
    private val nativeRoot: NativeRoot

    init {
        require(preopenRoot.isNotEmpty()) { "preopen root must not be empty" }
        require('\u0000' !in preopenRoot) { "preopen root must not contain NUL" }
        val descriptor = kwasm_wasi_open_root(preopenRoot)
        if (descriptor < 0) {
            val error = kwasm_wasi_errno()
            throw IllegalArgumentException(
                "preopen root '$preopenRoot' is not an accessible, non-symlink directory " +
                    "(errno $error)",
            )
        }
        nativeRoot = NativeRoot(descriptor)
    }

    /** This filesystem's root directory capability. */
    public val root: WasiDirectory
        get() = this

    override suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource =
        NativeDirectory(nativeRoot, emptyList()).open(path, options)

    override suspend fun statSelf(): WasiFileStat =
        pathBackend().statSelf()

    override suspend fun setTimesSelf(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ): Unit =
        pathBackend().setTimesSelf(accessTimeNanos, modificationTimeNanos)

    override suspend fun readEntries(): List<WasiDirectoryEntry> =
        pathBackend().readEntries()

    override suspend fun createDirectory(path: String): Unit =
        pathBackend().createDirectory(path)

    override suspend fun stat(
        path: String,
        followSymlinks: Boolean,
    ): WasiPathStat = pathBackend().stat(path, followSymlinks)

    override suspend fun setTimes(
        path: String,
        followSymlinks: Boolean,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ): Unit =
        pathBackend().setTimes(
            path,
            followSymlinks,
            accessTimeNanos,
            modificationTimeNanos,
        )

    override suspend fun link(
        sourcePath: String,
        followSourceSymlink: Boolean,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ): Unit =
        pathBackend().link(
            sourcePath,
            followSourceSymlink,
            targetDirectory,
            targetPath,
        )

    override suspend fun readLink(path: String): ByteArray =
        pathBackend().readLink(path)

    override suspend fun removeDirectory(path: String): Unit =
        pathBackend().removeDirectory(path)

    override suspend fun rename(
        sourcePath: String,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ): Unit =
        pathBackend().rename(sourcePath, targetDirectory, targetPath)

    override suspend fun createSymbolicLink(
        targetPath: String,
        linkPath: String,
    ): Unit =
        pathBackend().createSymbolicLink(targetPath, linkPath)

    override suspend fun unlinkFile(path: String): Unit =
        pathBackend().unlinkFile(path)

    internal fun pathBackend(): WasiDirectory =
        NativeDirectory(nativeRoot, emptyList())

    /** Close the root host descriptor and invalidate this directory capability. */
    public suspend fun close() {
        nativeRoot.close()
    }
}

/** Kotlin/Native-specific descriptive alias for [NativeFileSystem]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public typealias NativeWasiFileSystem = NativeFileSystem

private class NativeRoot(
    descriptor: Int,
) {
    private val mutex = Mutex()
    private var descriptor: Int = descriptor

    suspend fun <T> withDescriptor(block: (Int) -> T): T =
        mutex.withLock {
            val current = descriptor
            if (current < 0) nativeFail(WasiErrno.BADF)
            block(current)
        }

    suspend fun close() {
        mutex.withLock {
            val current = descriptor
            if (current < 0) return@withLock
            descriptor = -1
            if (kwasm_wasi_close(current) != 0) {
                nativeFail()
            }
        }
    }
}

private class NativeDirectory(
    private val root: NativeRoot,
    private val components: List<String>,
) : WasiDirectory {
    override suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource {
        val requested = guestPathComponents(path)
        if (path.isEmpty()) nativeFail(WasiErrno.NOENT)
        if (requested.isEmpty()) {
            if (options.create && options.exclusive) nativeFail(WasiErrno.EXIST)
            if (options.truncate) nativeFail(WasiErrno.ISDIR)
            return WasiOpenedDirectory(this)
        }
        return root.withDescriptor { rootDescriptor ->
            openFromRoot(rootDescriptor, requested, options)
        }
    }

    override suspend fun statSelf(): WasiFileStat =
        root.withDescriptor { rootDescriptor ->
            withDirectoryDescriptor(rootDescriptor) { directoryDescriptor ->
                nativeDescriptorStat(directoryDescriptor)
            }
        }

    override suspend fun setTimesSelf(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos == null && modificationTimeNanos == null) return
        root.withDescriptor { rootDescriptor ->
            withDirectoryDescriptor(rootDescriptor) { directoryDescriptor ->
                if (
                    kwasm_wasi_fd_set_times(
                        directoryDescriptor,
                        (accessTimeNanos != null).toInt(),
                        accessTimeNanos ?: 0uL,
                        (modificationTimeNanos != null).toInt(),
                        modificationTimeNanos ?: 0uL,
                    ) != 0
                ) {
                    nativeFail()
                }
            }
        }
    }

    override suspend fun readEntries(): List<WasiDirectoryEntry> =
        root.withDescriptor { rootDescriptor ->
            withDirectoryDescriptor(rootDescriptor, ::readNativeDirectoryEntries)
        }

    override suspend fun createDirectory(path: String) {
        val requested = requiredPath(path)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                if (kwasm_wasi_path_create_directory(parent, name) != 0) nativeFail()
            }
        }
    }

    override suspend fun stat(
        path: String,
        followSymlinks: Boolean,
    ): WasiPathStat {
        val requested = requiredPath(path)
        return root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                val stat = pathStat(parent, name)
                if (followSymlinks && stat.fileType == WasiFileType.SYMBOLIC_LINK) {
                    nativeFail(WasiErrno.NOTCAPABLE)
                }
                stat
            }
        }
    }

    override suspend fun setTimes(
        path: String,
        followSymlinks: Boolean,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos == null && modificationTimeNanos == null) return
        val requested = requiredPath(path)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                if (followSymlinks &&
                    pathStat(parent, name).fileType == WasiFileType.SYMBOLIC_LINK
                ) {
                    nativeFail(WasiErrno.NOTCAPABLE)
                }
                if (
                    kwasm_wasi_path_set_times(
                        parent,
                        name,
                        (accessTimeNanos != null).toInt(),
                        accessTimeNanos ?: 0uL,
                        (modificationTimeNanos != null).toInt(),
                        modificationTimeNanos ?: 0uL,
                    ) != 0
                ) {
                    nativeFail()
                }
            }
        }
    }

    override suspend fun link(
        sourcePath: String,
        followSourceSymlink: Boolean,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        if (followSourceSymlink) nativeFail(WasiErrno.NOTSUP)
        val target = targetDirectory.nativeDirectory()
        if (target.root !== root) nativeFail(WasiErrno.NOTSUP)
        val sourceRequested = requiredPath(sourcePath)
        val targetRequested = target.requiredPath(targetPath)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, sourceRequested) { sourceParent, sourceName ->
                target.withParentDescriptor(
                    rootDescriptor,
                    targetRequested,
                ) { targetParent, targetName ->
                    if (
                        kwasm_wasi_path_link(
                            sourceParent,
                            sourceName.withTrailingSeparatorFrom(sourcePath),
                            targetParent,
                            targetName.withTrailingSeparatorFrom(targetPath),
                        ) != 0
                    ) {
                        nativeFail()
                    }
                }
            }
        }
    }

    override suspend fun readLink(path: String): ByteArray {
        val requested = requiredPath(path)
        return root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                var capacity = INITIAL_LINK_BUFFER_BYTES
                while (capacity <= MAX_LINK_BYTES) {
                    val buffer = ByteArray(capacity)
                    val count = buffer.usePinned { pinned ->
                        kwasm_wasi_path_readlink(
                            parent,
                            name,
                            pinned.addressOf(0),
                            capacity.toULong(),
                        )
                    }
                    if (count < 0) nativeFail()
                    if (count < capacity) return@withParentDescriptor buffer.copyOf(count.toInt())
                    capacity *= 2
                }
                nativeFail(WasiErrno.NAMETOOLONG)
            }
        }
    }

    override suspend fun removeDirectory(path: String) {
        val requested = requiredPath(path)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                if (kwasm_wasi_path_remove_directory(parent, name) != 0) nativeFail()
            }
        }
    }

    override suspend fun rename(
        sourcePath: String,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        val target = targetDirectory.nativeDirectory()
        if (target.root !== root) nativeFail(WasiErrno.NOTSUP)
        val sourceRequested = requiredPath(sourcePath)
        val targetRequested = target.requiredPath(targetPath)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, sourceRequested) { sourceParent, sourceName ->
                target.withParentDescriptor(
                    rootDescriptor,
                    targetRequested,
                ) { targetParent, targetName ->
                    if (
                        kwasm_wasi_path_rename(
                            sourceParent,
                            sourceName,
                            targetParent,
                            targetName,
                        ) != 0
                    ) {
                        nativeFail()
                    }
                }
            }
        }
    }

    override suspend fun createSymbolicLink(
        targetPath: String,
        linkPath: String,
    ) {
        requireRelativeSymlinkTarget(targetPath)
        val requested = requiredPath(linkPath)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                if (
                    kwasm_wasi_path_symlink(
                        targetPath,
                        parent,
                        name.withTrailingSeparatorFrom(linkPath),
                    ) != 0
                ) {
                    nativeFail()
                }
            }
        }
    }

    override suspend fun unlinkFile(path: String) {
        val requested = requiredPath(path)
        root.withDescriptor { rootDescriptor ->
            withParentDescriptor(rootDescriptor, requested) { parent, name ->
                if (
                    kwasm_wasi_path_unlink_file(
                        parent,
                        name.withTrailingSeparatorFrom(path),
                    ) != 0
                ) {
                    nativeFail()
                }
            }
        }
    }

    private fun openFromRoot(
        rootDescriptor: Int,
        requested: List<String>,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource {
        var directoryDescriptor = openDirectoryAt(rootDescriptor, ".")
        var openedDescriptor = -1
        try {
            for (component in components + requested.dropLast(1)) {
                val next = openDirectoryAt(directoryDescriptor, component)
                closeIgnoringFailure(directoryDescriptor)
                directoryDescriptor = next
            }

            val name = requested.last()
            val openedComponents = components + requested
            if (options.requireDirectory) {
                openedDescriptor = openDirectoryAt(directoryDescriptor, name)
                if (options.truncate) nativeFail(WasiErrno.ISDIR)
                if (options.create && options.exclusive) nativeFail(WasiErrno.EXIST)
                closeIgnoringFailure(openedDescriptor)
                openedDescriptor = -1
                return WasiOpenedDirectory(NativeDirectory(root, openedComponents))
            }

            openedDescriptor = openFileAt(directoryDescriptor, name, options)
            val kind = kwasm_wasi_fd_kind(openedDescriptor)
            if (kind < 0) nativeFail()
            return when (kind) {
                NATIVE_REGULAR_FILE -> {
                    val file = NativeFileHandle(openedDescriptor)
                    openedDescriptor = -1
                    WasiOpenedFile(file)
                }
                NATIVE_DIRECTORY -> {
                    closeIgnoringFailure(openedDescriptor)
                    openedDescriptor = -1
                    WasiOpenedDirectory(NativeDirectory(root, openedComponents))
                }
                else -> nativeFail(WasiErrno.NOTSUP)
            }
        } finally {
            if (openedDescriptor >= 0) closeIgnoringFailure(openedDescriptor)
            closeIgnoringFailure(directoryDescriptor)
        }
    }

    private fun requiredPath(path: String): List<String> =
        guestPathComponents(path).also {
            if (it.isEmpty()) nativeFail(WasiErrno.NOENT)
        }

    private inline fun <T> withParentDescriptor(
        rootDescriptor: Int,
        requested: List<String>,
        block: (Int, String) -> T,
    ): T {
        var parent = openDirectoryAt(rootDescriptor, ".")
        try {
            for (component in components + requested.dropLast(1)) {
                val next = openDirectoryAt(parent, component)
                closeIgnoringFailure(parent)
                parent = next
            }
            return block(parent, requested.last())
        } finally {
            closeIgnoringFailure(parent)
        }
    }

    private inline fun <T> withDirectoryDescriptor(
        rootDescriptor: Int,
        block: (Int) -> T,
    ): T {
        var directory = openDirectoryAt(rootDescriptor, ".")
        try {
            for (component in components) {
                val next = openDirectoryAt(directory, component)
                closeIgnoringFailure(directory)
                directory = next
            }
            return block(directory)
        } finally {
            closeIgnoringFailure(directory)
        }
    }
}

private fun String.withTrailingSeparatorFrom(path: String): String =
    if (path.endsWith('/')) "$this/" else this

private fun WasiDirectory.nativeDirectory(): NativeDirectory =
    when (this) {
        is NativeDirectory -> this
        is NativeFileSystem -> pathBackend() as NativeDirectory
        else -> nativeFail(WasiErrno.NOTSUP)
    }

private fun pathStat(
    directoryDescriptor: Int,
    name: String,
): WasiPathStat {
    val values = ULongArray(NATIVE_PATH_STAT_FIELDS)
    val result = values.usePinned { pinned ->
        kwasm_wasi_path_stat(directoryDescriptor, name, pinned.addressOf(0))
    }
    if (result != 0) nativeFail()
    val fileType =
        when (values[0].toInt()) {
            NATIVE_REGULAR_FILE -> WasiFileType.REGULAR_FILE
            NATIVE_DIRECTORY -> WasiFileType.DIRECTORY
            NATIVE_SYMBOLIC_LINK -> WasiFileType.SYMBOLIC_LINK
            else -> WasiFileType.UNKNOWN
        }
    return WasiPathStat(
        fileType = fileType,
        attributes = WasiFileStat(
            deviceId = values[1],
            inode = values[2],
            linkCount = values[3],
            size = values[4],
            accessTimeNanos = values[5],
            modificationTimeNanos = values[6],
            changeTimeNanos = values[7],
        ),
    )
}

private fun nativeDescriptorStat(descriptor: Int): WasiFileStat {
    val values = ULongArray(NATIVE_FILE_STAT_FIELDS)
    val result = values.usePinned { pinned ->
        kwasm_wasi_fd_stat(descriptor, pinned.addressOf(0))
    }
    if (result != 0) nativeFail()
    return WasiFileStat(
        deviceId = values[0],
        inode = values[1],
        linkCount = values[2],
        size = values[3],
        accessTimeNanos = values[4],
        modificationTimeNanos = values[5],
        changeTimeNanos = values[6],
    )
}

private fun readNativeDirectoryEntries(descriptor: Int): List<WasiDirectoryEntry> {
    val entries = mutableListOf<WasiDirectoryEntry>()
    var startIndex = 0uL
    while (true) {
        val records = ULongArray(
            NATIVE_DIRECTORY_BATCH_ENTRIES * NATIVE_DIRECTORY_RECORD_FIELDS,
        )
        val names = ByteArray(NATIVE_DIRECTORY_NAME_BYTES)
        val result = ULongArray(NATIVE_DIRECTORY_RESULT_FIELDS)
        val status = records.usePinned { recordBuffer ->
            names.usePinned { nameBuffer ->
                result.usePinned { resultBuffer ->
                    kwasm_wasi_directory_read_batch(
                        descriptor,
                        startIndex,
                        recordBuffer.addressOf(0),
                        NATIVE_DIRECTORY_BATCH_ENTRIES.toULong(),
                        nameBuffer.addressOf(0),
                        names.size.toULong(),
                        resultBuffer.addressOf(0),
                    )
                }
            }
        }
        if (status != 0) nativeFail()
        val recordCount = result[0].boundedInt(NATIVE_DIRECTORY_BATCH_ENTRIES)
        val nameBytes = result[1].boundedInt(names.size)
        val complete =
            when (result[2]) {
                0uL -> false
                1uL -> true
                else -> nativeFail(WasiErrno.IO)
            }
        for (index in 0 until recordCount) {
            val recordOffset = index * NATIVE_DIRECTORY_RECORD_FIELDS
            val nameOffset = records[recordOffset + 1].boundedInt(nameBytes)
            val nameLength = records[recordOffset + 2].boundedInt(nameBytes)
            if (nameLength > nameBytes - nameOffset) nativeFail(WasiErrno.IO)
            val name =
                try {
                    names.copyOfRange(
                        nameOffset,
                        nameOffset + nameLength,
                    ).decodeToString(throwOnInvalidSequence = true)
                } catch (_: IllegalArgumentException) {
                    nativeFail(WasiErrno.ILSEQ)
                }
            if (name.isEmpty() || name == "." || name == ".." || '/' in name) {
                nativeFail(WasiErrno.IO)
            }
            entries += WasiDirectoryEntry(
                name = name,
                fileType =
                    when (records[recordOffset + 3].toInt()) {
                        NATIVE_REGULAR_FILE -> WasiFileType.REGULAR_FILE
                        NATIVE_DIRECTORY -> WasiFileType.DIRECTORY
                        NATIVE_SYMBOLIC_LINK -> WasiFileType.SYMBOLIC_LINK
                        else -> WasiFileType.UNKNOWN
                    },
                inode = records[recordOffset],
            )
        }
        if (entries.size > MAX_NATIVE_DIRECTORY_ENTRIES) {
            nativeFail(WasiErrno.NOMEM)
        }
        if (complete) return entries.sortedBy { it.name }
        if (recordCount == 0) nativeFail(WasiErrno.IO)
        if (startIndex > ULong.MAX_VALUE - recordCount.toULong()) {
            nativeFail(WasiErrno.OVERFLOW)
        }
        startIndex += recordCount.toULong()
    }
}

private fun ULong.boundedInt(maximum: Int): Int {
    if (this > maximum.toULong()) nativeFail(WasiErrno.IO)
    return toInt()
}

private class NativeFileHandle(
    descriptor: Int,
) : WasiFileHandle {
    private val mutex = Mutex()
    private var descriptor: Int = descriptor

    override suspend fun size(): Long =
        withDescriptor { current ->
            val size = kwasm_wasi_fd_size(current)
            if (size < 0) nativeFail()
            size
        }

    override suspend fun stat(): WasiFileStat =
        withDescriptor { current ->
            nativeDescriptorStat(current)
        }

    override suspend fun read(
        position: Long,
        maximumBytes: Int,
    ): ByteArray {
        if (position < 0 || maximumBytes < 0) nativeFail(WasiErrno.INVAL)
        if (maximumBytes == 0) return ByteArray(0)
        return withDescriptor { current ->
            val bytes = ByteArray(maximumBytes)
            val count = bytes.usePinned { pinned ->
                retryInterrupted {
                    kwasm_wasi_pread(
                        current,
                        pinned.addressOf(0),
                        maximumBytes.toULong(),
                        position,
                    )
                }
            }
            if (count < 0) nativeFail()
            bytes.copyOf(count.toInt())
        }
    }

    override suspend fun write(
        position: Long,
        bytes: ByteArray,
    ): Int {
        if (position < 0) nativeFail(WasiErrno.INVAL)
        if (bytes.isEmpty()) return 0
        return withDescriptor { current ->
            val count = bytes.usePinned { pinned ->
                retryInterrupted {
                    kwasm_wasi_pwrite(
                        current,
                        pinned.addressOf(0),
                        bytes.size.toULong(),
                        position,
                    )
                }
            }
            if (count < 0) nativeFail()
            count.toInt()
        }
    }

    override suspend fun setSize(size: Long) {
        if (size < 0) nativeFail(WasiErrno.INVAL)
        withDescriptor { current ->
            if (kwasm_wasi_fd_truncate(current, size) != 0) nativeFail()
        }
    }

    override suspend fun sync(dataOnly: Boolean) {
        withDescriptor { current ->
            val result = retryInterrupted {
                if (dataOnly) {
                    kwasm_wasi_fd_datasync(current).toLong()
                } else {
                    kwasm_wasi_fd_sync(current).toLong()
                }
            }
            if (result != 0L) nativeFail()
        }
    }

    override suspend fun setTimes(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos == null && modificationTimeNanos == null) return
        withDescriptor { current ->
            if (
                kwasm_wasi_fd_set_times(
                    current,
                    (accessTimeNanos != null).toInt(),
                    accessTimeNanos ?: 0uL,
                    (modificationTimeNanos != null).toInt(),
                    modificationTimeNanos ?: 0uL,
                ) != 0
            ) {
                nativeFail()
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            val current = descriptor
            if (current < 0) return@withLock
            descriptor = -1
            // Never retry close(2) after EINTR: the descriptor may already have
            // been released and reused by the host.
            if (kwasm_wasi_close(current) != 0) nativeFail()
        }
    }

    private suspend fun <T> withDescriptor(block: (Int) -> T): T =
        mutex.withLock {
            val current = descriptor
            if (current < 0) nativeFail(WasiErrno.BADF)
            block(current)
        }
}

private fun openDirectoryAt(
    directoryDescriptor: Int,
    name: String,
): Int {
    val descriptor = kwasm_wasi_open_directory_at(directoryDescriptor, name)
    if (descriptor < 0) nativeFail()
    return descriptor
}

private fun openFileAt(
    directoryDescriptor: Int,
    name: String,
    options: WasiPathOpenOptions,
): Int {
    val descriptor = kwasm_wasi_open_file_at(
        directoryDescriptor,
        name,
        options.read.toInt(),
        options.write.toInt(),
        options.create.toInt(),
        options.exclusive.toInt(),
        options.truncate.toInt(),
    )
    if (descriptor < 0) {
        val error = kwasm_wasi_errno()
        /*
         * POSIX rejects opening a directory with O_WRONLY/O_RDWR before we can
         * inspect its type. WASI can still request write rights on a directory;
         * match the backend-neutral contract by reopening it read-only.
         */
        if (error == EISDIR && !options.truncate && !options.exclusive) {
            return openDirectoryAt(directoryDescriptor, name)
        }
        nativeFail(error)
    }
    return descriptor
}

private inline fun retryInterrupted(operation: () -> Long): Long {
    while (true) {
        val result = operation()
        if (result >= 0 || kwasm_wasi_errno() != EINTR) return result
    }
}

private fun closeIgnoringFailure(descriptor: Int) {
    if (descriptor >= 0) kwasm_wasi_close(descriptor)
}

private fun Boolean.toInt(): Int = if (this) 1 else 0

private fun nativeFail(errno: WasiErrno): Nothing =
    throw WasiFileSystemException(errno)

private fun nativeFail(): Nothing =
    nativeFail(kwasm_wasi_errno())

private fun nativeFail(error: Int): Nothing {
    val errno =
        when (error) {
            ENOENT -> WasiErrno.NOENT
            EEXIST -> WasiErrno.EXIST
            EACCES, EPERM -> WasiErrno.ACCES
            ENOTDIR -> WasiErrno.NOTDIR
            ENOTEMPTY -> WasiErrno.NOTEMPTY
            EISDIR -> WasiErrno.ISDIR
            // Following links without an openat2-style "beneath" constraint is
            // not sound on every supported Native target, so links fail closed.
            ELOOP -> WasiErrno.NOTCAPABLE
            EBADF -> WasiErrno.BADF
            EINVAL -> WasiErrno.INVAL
            EFBIG -> WasiErrno.FBIG
            ENAMETOOLONG -> WasiErrno.NAMETOOLONG
            EOVERFLOW -> WasiErrno.OVERFLOW
            EROFS -> WasiErrno.ROFS
            EMFILE -> WasiErrno.MFILE
            ENOMEM -> WasiErrno.NOMEM
            ENOSYS, ENOTSUP -> WasiErrno.NOTSUP
            EIO -> WasiErrno.IO
            else -> WasiErrno.IO
        }
    throw WasiFileSystemException(errno)
}

private const val NATIVE_REGULAR_FILE: Int = 1
private const val NATIVE_DIRECTORY: Int = 2
private const val NATIVE_SYMBOLIC_LINK: Int = 3
private const val NATIVE_FILE_STAT_FIELDS: Int = 7
private const val NATIVE_PATH_STAT_FIELDS: Int = 8
private const val NATIVE_DIRECTORY_RECORD_FIELDS: Int = 4
private const val NATIVE_DIRECTORY_RESULT_FIELDS: Int = 3
private const val NATIVE_DIRECTORY_BATCH_ENTRIES: Int = 256
private const val NATIVE_DIRECTORY_NAME_BYTES: Int = 64 * 1024
private const val MAX_NATIVE_DIRECTORY_ENTRIES: Int = 1_000_000
private const val INITIAL_LINK_BUFFER_BYTES: Int = 256
private const val MAX_LINK_BYTES: Int = 1 shl 20
