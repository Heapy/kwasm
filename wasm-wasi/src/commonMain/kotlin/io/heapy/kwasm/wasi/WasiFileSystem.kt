package io.heapy.kwasm.wasi

/**
 * A directory capability that can resolve only paths beneath itself.
 *
 * Implementations must reject paths that escape the granted directory.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface WasiDirectory {
    /** Open [path] relative to this directory capability. */
    public suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource

    /** Attributes for the directory represented by this capability. */
    public suspend fun statSelf(): WasiFileStat = WasiFileStat(size = 0u)

    /**
     * Change selected timestamps on the directory represented by this
     * capability.
     *
     * A null timestamp leaves that field unchanged.
     */
    public suspend fun setTimesSelf(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos != null || modificationTimeNanos != null) {
            throw WasiFileSystemException(WasiErrno.NOTSUP)
        }
    }

    /**
     * Enumerate the directory's direct children.
     *
     * The common Preview 1 descriptor layer supplies the synthetic `.` and
     * `..` entries and assigns resumable directory cookies. Backends return
     * only real children and should use a stable name for each entry.
     */
    public suspend fun readEntries(): List<WasiDirectoryEntry> {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /** Create one directory at [path]. Missing parents are not created. */
    public suspend fun createDirectory(path: String) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /** Read attributes for [path], optionally following its final symlink. */
    public suspend fun stat(
        path: String,
        followSymlinks: Boolean,
    ): WasiPathStat {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /**
     * Change selected timestamps for [path].
     *
     * A null timestamp leaves that field unchanged.
     */
    public suspend fun setTimes(
        path: String,
        followSymlinks: Boolean,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos != null || modificationTimeNanos != null) {
            throw WasiFileSystemException(WasiErrno.NOTSUP)
        }
    }

    /**
     * Create a hard link to [sourcePath] at [targetPath].
     *
     * Implementations must reject incompatible or unconfined
     * [targetDirectory] capabilities.
     */
    public suspend fun link(
        sourcePath: String,
        followSourceSymlink: Boolean,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /** Return the uninterpreted bytes stored in the symlink at [path]. */
    public suspend fun readLink(path: String): ByteArray {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /** Remove the empty directory at [path]. */
    public suspend fun removeDirectory(path: String) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /**
     * Atomically move [sourcePath] to [targetPath] where the backend supports
     * that operation.
     */
    public suspend fun rename(
        sourcePath: String,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /** Create a symbolic link at [linkPath] containing [targetPath]. */
    public suspend fun createSymbolicLink(
        targetPath: String,
        linkPath: String,
    ) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /** Remove the non-directory node at [path]. */
    public suspend fun unlinkFile(path: String) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }
}

/** One direct child returned by [WasiDirectory.readEntries]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiDirectoryEntry(
    public val name: String,
    public val fileType: WasiFileType,
    public val inode: ULong = 0u,
)

/** Backend-neutral options used to implement WASI `path_open`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiPathOpenOptions(
    public val create: Boolean = false,
    public val exclusive: Boolean = false,
    public val truncate: Boolean = false,
    public val requireDirectory: Boolean = false,
    public val read: Boolean = false,
    public val write: Boolean = false,
    public val followSymlinks: Boolean = false,
)

/** A resource returned by [WasiDirectory.open]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed interface WasiOpenedResource

/** A newly opened regular-file handle. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiOpenedFile(
    public val file: WasiFileHandle,
) : WasiOpenedResource

/** A child directory capability. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiOpenedDirectory(
    public val directory: WasiDirectory,
) : WasiOpenedResource

/**
 * Backend-neutral filesystem attributes used by WASI `filestat`.
 *
 * Backends that cannot provide stable device or inode identifiers may leave
 * those fields at zero, as permitted by Preview 1.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiFileStat(
    public val deviceId: ULong = 0u,
    public val inode: ULong = 0u,
    public val linkCount: ULong = 1u,
    public val size: ULong,
    public val accessTimeNanos: ULong = 0u,
    public val modificationTimeNanos: ULong = 0u,
    public val changeTimeNanos: ULong = 0u,
)

/** A path's file type and backend-neutral Preview 1 attributes. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiPathStat(
    public val fileType: WasiFileType,
    public val attributes: WasiFileStat,
)

/**
 * Positioned regular-file I/O used by the common WASI descriptor table.
 *
 * The descriptor table owns the current offset, append behavior, rights, and
 * lifecycle; a backend only supplies positioned storage operations.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface WasiFileHandle {
    /** Current file size in bytes. */
    public suspend fun size(): Long

    /** Current regular-file attributes. */
    public suspend fun stat(): WasiFileStat {
        val currentSize = size()
        if (currentSize < 0) throw WasiFileSystemException(WasiErrno.IO)
        return WasiFileStat(size = currentSize.toULong())
    }

    /** Read at most [maximumBytes] starting at [position]. */
    public suspend fun read(
        position: Long,
        maximumBytes: Int,
    ): ByteArray

    /** Write a prefix of [bytes] starting at [position]. */
    public suspend fun write(
        position: Long,
        bytes: ByteArray,
    ): Int

    /**
     * Change the logical file size, zero-filling any newly exposed region.
     *
     * The default keeps existing third-party handles source-compatible while
     * reporting that mutation is unsupported.
     */
    public suspend fun setSize(size: Long) {
        if (size < 0) throw WasiFileSystemException(WasiErrno.INVAL)
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /**
     * Ensure that `[offset, offset + length)` is allocated.
     *
     * Backends without a dedicated allocation primitive get the portable
     * Preview 1 behavior of extending the logical size when necessary.
     */
    public suspend fun allocate(
        offset: Long,
        length: Long,
    ) {
        if (offset < 0 || length < 0 || offset > Long.MAX_VALUE - length) {
            throw WasiFileSystemException(WasiErrno.FBIG)
        }
        val requiredSize = offset + length
        if (requiredSize > size()) setSize(requiredSize)
    }

    /**
     * Apply an access-pattern hint. Advice is optional by definition, so the
     * portable default validates in the descriptor layer and otherwise no-ops.
     */
    public suspend fun advise(
        offset: Long,
        length: Long,
        advice: Int,
    ): Unit = Unit

    /**
     * Flush pending file changes to durable storage.
     *
     * When [dataOnly] is true, metadata not required to retrieve the file's
     * contents may be omitted. The default fails closed because silently
     * claiming durability would violate Preview 1's synchronization contract.
     */
    public suspend fun sync(dataOnly: Boolean) {
        throw WasiFileSystemException(WasiErrno.NOTSUP)
    }

    /**
     * Change selected file timestamps. A null value leaves that timestamp
     * unchanged.
     */
    public suspend fun setTimes(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos != null || modificationTimeNanos != null) {
            throw WasiFileSystemException(WasiErrno.NOTSUP)
        }
    }

    /** Release backend resources owned by this handle. */
    public suspend fun close()
}

/** A filesystem failure that maps directly to a WASI Preview 1 errno. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasiFileSystemException(
    public val errno: WasiErrno,
    cause: Throwable? = null,
) : Exception("WASI filesystem operation failed with $errno", cause)

internal fun guestPathComponents(path: String): List<String> {
    if (path.isEmpty()) return emptyList()
    if (path.startsWith('/')) throw WasiFileSystemException(WasiErrno.NOTCAPABLE)
    if ('\u0000' in path) throw WasiFileSystemException(WasiErrno.INVAL)
    val components = mutableListOf<String>()
    path.split('/').forEach { component ->
        when (component) {
            "", "." -> Unit
            ".." -> {
                if (components.isEmpty()) {
                    throw WasiFileSystemException(WasiErrno.NOTCAPABLE)
                }
                components.removeAt(components.lastIndex)
            }
            else -> components += component
        }
    }
    return components
}

internal fun requireRelativeSymlinkTarget(targetPath: String) {
    if ('\u0000' in targetPath) throw WasiFileSystemException(WasiErrno.INVAL)
    if (targetPath.startsWith('/')) {
        throw WasiFileSystemException(WasiErrno.NOTCAPABLE)
    }
}
