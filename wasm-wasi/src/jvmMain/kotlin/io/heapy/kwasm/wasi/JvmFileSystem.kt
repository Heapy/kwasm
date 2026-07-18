package io.heapy.kwasm.wasi

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A `java.nio.file` WASI directory capability confined to [preopenRoot].
 *
 * Guest paths are always relative. Both lexical traversal and resolved
 * symlink traversal outside the canonical root are rejected.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class JvmFileSystem(root: Path) : WasiDirectory {
    /** Canonical host directory represented by this capability. */
    public val preopenRoot: Path = canonicalPreopenRoot(root)

    /** This filesystem's root directory capability. */
    public val root: WasiDirectory
        get() = this

    private val directory: JvmDirectory = JvmDirectory(preopenRoot, preopenRoot)

    /** Construct a capability from a JVM [File]. */
    public constructor(root: File) : this(root.toPath())

    /** Construct a capability from a host path string. */
    public constructor(root: String) : this(Path.of(root))

    override suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource = directory.open(path, options)

    override suspend fun statSelf(): WasiFileStat =
        directory.statSelf()

    override suspend fun setTimesSelf(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ): Unit =
        directory.setTimesSelf(accessTimeNanos, modificationTimeNanos)

    override suspend fun readEntries(): List<WasiDirectoryEntry> =
        directory.readEntries()

    override suspend fun createDirectory(path: String): Unit =
        directory.createDirectory(path)

    override suspend fun stat(
        path: String,
        followSymlinks: Boolean,
    ): WasiPathStat = directory.stat(path, followSymlinks)

    override suspend fun setTimes(
        path: String,
        followSymlinks: Boolean,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ): Unit =
        directory.setTimes(
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
        directory.link(
            sourcePath,
            followSourceSymlink,
            targetDirectory,
            targetPath,
        )

    override suspend fun readLink(path: String): ByteArray =
        directory.readLink(path)

    override suspend fun removeDirectory(path: String): Unit =
        directory.removeDirectory(path)

    override suspend fun rename(
        sourcePath: String,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ): Unit =
        directory.rename(sourcePath, targetDirectory, targetPath)

    override suspend fun createSymbolicLink(
        targetPath: String,
        linkPath: String,
    ): Unit =
        directory.createSymbolicLink(targetPath, linkPath)

    override suspend fun unlinkFile(path: String): Unit =
        directory.unlinkFile(path)

    internal fun pathBackend(): WasiDirectory = directory
}

/** JVM-specific descriptive alias for [JvmFileSystem]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public typealias JvmWasiFileSystem = JvmFileSystem

private class JvmDirectory(
    private val preopenRoot: Path,
    private val directory: Path,
) : WasiDirectory {
    override suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource = withContext(Dispatchers.IO) {
        openBlocking(path, options)
    }

    override suspend fun statSelf(): WasiFileStat = withContext(Dispatchers.IO) {
        readWasiPathStat(confinedRealDirectory(directory)).attributes
    }

    override suspend fun setTimesSelf(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos == null && modificationTimeNanos == null) return
        withContext(Dispatchers.IO) {
            setBasicTimesBlocking(
                path = confinedRealDirectory(directory),
                linkOptions = emptyArray(),
                accessTimeNanos = accessTimeNanos,
                modificationTimeNanos = modificationTimeNanos,
            )
        }
    }

    override suspend fun readEntries(): List<WasiDirectoryEntry> =
        withContext(Dispatchers.IO) {
            val current = confinedRealDirectory(directory)
            nio {
                Files.newDirectoryStream(current).use { entries ->
                    entries.map { entry ->
                        val stat = readWasiPathStat(entry)
                        WasiDirectoryEntry(
                            name = entry.fileName.toString(),
                            fileType = stat.fileType,
                            inode = stat.attributes.inode,
                        )
                    }.sortedBy { it.name }
                }
            }
        }

    override suspend fun createDirectory(path: String) {
        withContext(Dispatchers.IO) {
            nio { Files.createDirectory(resolveEntry(path)) }
        }
    }

    override suspend fun stat(
        path: String,
        followSymlinks: Boolean,
    ): WasiPathStat = withContext(Dispatchers.IO) {
        val entry = resolveEntry(path)
        val inspected =
            if (followSymlinks) confinedRealPath(entry) else entry
        readWasiPathStat(inspected)
    }

    override suspend fun setTimes(
        path: String,
        followSymlinks: Boolean,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos == null && modificationTimeNanos == null) return
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(path)
            val inspected =
                if (followSymlinks) confinedRealPath(entry) else entry
            val linkOptions =
                if (followSymlinks) emptyArray()
                else arrayOf(LinkOption.NOFOLLOW_LINKS)
            setBasicTimesBlocking(
                path = inspected,
                linkOptions = linkOptions,
                accessTimeNanos = accessTimeNanos,
                modificationTimeNanos = modificationTimeNanos,
            )
        }
    }

    private fun setBasicTimesBlocking(
        path: Path,
        linkOptions: Array<LinkOption>,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        val view = nio {
            Files.getFileAttributeView(
                path,
                BasicFileAttributeView::class.java,
                *linkOptions,
            )
        } ?: fail(WasiErrno.NOTSUP)
        nio {
            view.setTimes(
                modificationTimeNanos?.toFileTime(),
                accessTimeNanos?.toFileTime(),
                null,
            )
        }
    }

    override suspend fun link(
        sourcePath: String,
        followSourceSymlink: Boolean,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        withContext(Dispatchers.IO) {
            val target = targetDirectory.jvmDirectory()
            target.rejectTrailingCreationTarget(targetPath)
            val sourceEntry = resolveEntry(sourcePath)
            val source =
                if (followSourceSymlink) confinedRealPath(sourceEntry)
                else sourceEntry.also(::readAttributes)
            if (readAttributes(source).isDirectory) fail(WasiErrno.PERM)
            nio { Files.createLink(target.resolveEntry(targetPath), source) }
        }
    }

    override suspend fun readLink(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(path)
            if (!readAttributes(entry).isSymbolicLink) fail(WasiErrno.INVAL)
            nio { Files.readSymbolicLink(entry).toString().encodeToByteArray() }
        }

    override suspend fun removeDirectory(path: String) {
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(path)
            if (!readAttributes(entry).isDirectory) fail(WasiErrno.NOTDIR)
            nio { Files.delete(entry) }
        }
    }

    override suspend fun rename(
        sourcePath: String,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        withContext(Dispatchers.IO) {
            val target = targetDirectory.jvmDirectory()
            val sourceEntry = resolveEntry(sourcePath).also(::readAttributes)
            val targetEntry = target.resolveEntry(targetPath)
            nio {
                Files.move(
                    sourceEntry,
                    targetEntry,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    override suspend fun createSymbolicLink(
        targetPath: String,
        linkPath: String,
    ) {
        requireRelativeSymlinkTarget(targetPath)
        withContext(Dispatchers.IO) {
            rejectTrailingCreationTarget(linkPath)
            val target =
                try {
                    Path.of(targetPath)
                } catch (failure: InvalidPathException) {
                    fail(WasiErrno.INVAL, failure)
                }
            nio { Files.createSymbolicLink(resolveEntry(linkPath), target) }
        }
    }

    override suspend fun unlinkFile(path: String) {
        withContext(Dispatchers.IO) {
            rejectTrailingUnlinkTarget(path)
            val entry = resolveEntry(path)
            if (readAttributes(entry).isDirectory) fail(WasiErrno.ISDIR)
            nio { Files.delete(entry) }
        }
    }

    private fun rejectTrailingCreationTarget(path: String) {
        guestPathComponents(path)
        if (!path.endsWith('/')) return
        val attributes = attributesOrNull(resolveEntry(path)) ?: fail(WasiErrno.NOENT)
        fail(if (attributes.isDirectory) WasiErrno.EXIST else WasiErrno.NOTDIR)
    }

    private fun rejectTrailingUnlinkTarget(path: String) {
        guestPathComponents(path)
        if (!path.endsWith('/')) return
        val attributes = readAttributes(resolveEntry(path))
        fail(if (attributes.isDirectory) WasiErrno.ISDIR else WasiErrno.NOTDIR)
    }

    private fun openBlocking(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource {
        val components = guestPathComponents(path)
        if (path.isEmpty()) fail(WasiErrno.NOENT)
        if (components.isEmpty()) {
            if (options.create && options.exclusive) fail(WasiErrno.EXIST)
            if (options.truncate) fail(WasiErrno.ISDIR)
            return WasiOpenedDirectory(this)
        }

        var current = confinedRealDirectory(directory)
        components.forEachIndexed { index, component ->
            val last = index == components.lastIndex
            val name = safeHostName(component)
            val candidate = current.resolve(name).normalize()
            if (!candidate.startsWith(current) || !candidate.startsWith(preopenRoot)) {
                fail(WasiErrno.NOTCAPABLE)
            }

            val attributes = attributesOrNull(candidate)
            if (attributes == null) {
                if (!last) fail(WasiErrno.NOENT)
                return openMissing(candidate, options)
            }

            val actual =
                if (attributes.isSymbolicLink) {
                    val resolved = confinedRealPath(candidate)
                    if (last && !options.followSymlinks) fail(WasiErrno.LOOP)
                    resolved
                } else {
                    confinedRealPath(candidate)
                }

            if (!last) {
                if (!nio { Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS) }) {
                    fail(WasiErrno.NOTDIR)
                }
                current = actual
            } else {
                return openExisting(actual, options)
            }
        }
        fail(WasiErrno.NOENT)
    }

    private fun openMissing(
        path: Path,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource {
        if (!options.create) fail(WasiErrno.NOENT)
        if (options.requireDirectory) fail(WasiErrno.NOENT)
        val channel = openChannel(path, options, newFile = true)
        return validateOpenedChannel(path, channel)
    }

    private fun openExisting(
        path: Path,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource {
        val attributes = nio {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }
        if (options.create && options.exclusive) fail(WasiErrno.EXIST)
        if (attributes.isDirectory) {
            if (options.truncate) fail(WasiErrno.ISDIR)
            return WasiOpenedDirectory(JvmDirectory(preopenRoot, path))
        }
        if (options.requireDirectory) fail(WasiErrno.NOTDIR)
        if (!attributes.isRegularFile) fail(WasiErrno.NOTSUP)
        val channel = openChannel(path, options, newFile = false)
        return validateOpenedChannel(path, channel)
    }

    private fun openChannel(
        path: Path,
        options: WasiPathOpenOptions,
        newFile: Boolean,
    ): FileChannel {
        val openOptions = linkedSetOf<OpenOption>()
        if (options.read) openOptions += StandardOpenOption.READ
        if (options.write || options.truncate || newFile) {
            openOptions += StandardOpenOption.WRITE
        }
        if (openOptions.isEmpty()) openOptions += StandardOpenOption.READ
        if (options.truncate) openOptions += StandardOpenOption.TRUNCATE_EXISTING
        if (newFile) {
            openOptions +=
                if (options.exclusive) StandardOpenOption.CREATE_NEW
                else StandardOpenOption.CREATE
        }
        openOptions += LinkOption.NOFOLLOW_LINKS

        return try {
            nio { FileChannel.open(path, openOptions) }
        } catch (failure: WasiFileSystemException) {
            if (nio { Files.isSymbolicLink(path) }) fail(WasiErrno.NOTCAPABLE, failure)
            throw failure
        }
    }

    private fun validateOpenedChannel(
        path: Path,
        channel: FileChannel,
    ): WasiOpenedFile {
        try {
            confinedRealPath(path)
        } catch (failure: Throwable) {
            try {
                channel.close()
            } catch (_: IOException) {
                // Preserve the confinement failure.
            }
            throw failure
        }
        return WasiOpenedFile(NioFileHandle(channel, path))
    }

    private fun confinedRealDirectory(path: Path): Path {
        val real = confinedRealPath(path)
        if (!nio { Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) }) {
            fail(WasiErrno.NOTDIR)
        }
        return real
    }

    private fun confinedRealPath(path: Path): Path {
        val real = nio { path.toRealPath() }
        if (!real.startsWith(preopenRoot)) fail(WasiErrno.NOTCAPABLE)
        return real
    }

    private fun resolveEntry(path: String): Path {
        val components = guestPathComponents(path)
        if (components.isEmpty()) fail(WasiErrno.NOENT)
        var parent = confinedRealDirectory(directory)
        for (component in components.dropLast(1)) {
            val candidate = parent.resolve(safeHostName(component)).normalize()
            if (!candidate.startsWith(parent) || !candidate.startsWith(preopenRoot)) {
                fail(WasiErrno.NOTCAPABLE)
            }
            parent = confinedRealDirectory(candidate)
        }
        val entry = parent.resolve(safeHostName(components.last())).normalize()
        if (!entry.startsWith(parent) || !entry.startsWith(preopenRoot)) {
            fail(WasiErrno.NOTCAPABLE)
        }
        return entry
    }

    private fun readAttributes(path: Path): BasicFileAttributes =
        nio {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }

    private fun attributesOrNull(path: Path): BasicFileAttributes? =
        try {
            nio {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
        } catch (failure: WasiFileSystemException) {
            if (failure.errno == WasiErrno.NOENT) null else throw failure
        }

    private fun safeHostName(component: String): Path {
        val name =
            try {
                Path.of(component)
            } catch (failure: InvalidPathException) {
                fail(WasiErrno.INVAL, failure)
            }
        if (name.isAbsolute || name.root != null || name.nameCount != 1 ||
            name.fileName.toString() == ".."
        ) {
            fail(WasiErrno.NOTCAPABLE)
        }
        return name
    }
}

private fun WasiDirectory.jvmDirectory(): JvmDirectory =
    when (this) {
        is JvmDirectory -> this
        is JvmFileSystem -> pathBackend() as JvmDirectory
        else -> fail(WasiErrno.NOTSUP)
    }

private fun readWasiPathStat(path: Path): WasiPathStat {
    val basic = nio {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
    return basic.toWasiPathStat(readUnixMetadata(path))
}

private fun readUnixMetadata(path: Path): UnixFileMetadata? =
    try {
        val attributes = Files.readAttributes(
            path,
            "unix:dev,ino,nlink",
            LinkOption.NOFOLLOW_LINKS,
        )
        UnixFileMetadata(
            deviceId = (attributes["dev"] as? Number)?.toLong()?.toULong() ?: 0u,
            inode = (attributes["ino"] as? Number)?.toLong()?.toULong() ?: 0u,
            linkCount = (attributes["nlink"] as? Number)
                ?.toLong()
                ?.coerceAtLeast(0)
                ?.toULong()
                ?: 1u,
        )
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    }

private fun BasicFileAttributes.toWasiPathStat(
    unix: UnixFileMetadata?,
): WasiPathStat {
    val fileType =
        when {
            isRegularFile -> WasiFileType.REGULAR_FILE
            isDirectory -> WasiFileType.DIRECTORY
            isSymbolicLink -> WasiFileType.SYMBOLIC_LINK
            else -> WasiFileType.UNKNOWN
        }
    return WasiPathStat(
        fileType = fileType,
        attributes = WasiFileStat(
            deviceId = unix?.deviceId ?: 0u,
            inode = unix?.inode ?: 0u,
            linkCount = unix?.linkCount ?: 1u,
            size = size().coerceAtLeast(0).toULong(),
            accessTimeNanos = lastAccessTime().toNanos(),
            modificationTimeNanos = lastModifiedTime().toNanos(),
            changeTimeNanos = creationTime().toNanos(),
        ),
    )
}

private data class UnixFileMetadata(
    val deviceId: ULong,
    val inode: ULong,
    val linkCount: ULong,
)

private fun FileTime.toNanos(): ULong =
    to(TimeUnit.NANOSECONDS).coerceAtLeast(0).toULong()

private fun ULong.toFileTime(): FileTime {
    if (this > Long.MAX_VALUE.toULong()) fail(WasiErrno.OVERFLOW)
    return FileTime.from(toLong(), TimeUnit.NANOSECONDS)
}

private class NioFileHandle(
    private val channel: FileChannel,
    private val path: Path,
) : WasiFileHandle {
    override suspend fun size(): Long = withContext(Dispatchers.IO) {
        nio { channel.size() }
    }

    override suspend fun stat(): WasiFileStat = withContext(Dispatchers.IO) {
        val attributes = readWasiPathStat(path).attributes
        attributes.copy(size = nio { channel.size() }.coerceAtLeast(0).toULong())
    }

    override suspend fun read(
        position: Long,
        maximumBytes: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (position < 0 || maximumBytes < 0) fail(WasiErrno.INVAL)
        if (maximumBytes == 0) return@withContext ByteArray(0)
        val buffer = ByteBuffer.allocate(maximumBytes)
        val count = nio { channel.read(buffer, position) }
        if (count < 0) ByteArray(0) else buffer.array().copyOf(count)
    }

    override suspend fun write(
        position: Long,
        bytes: ByteArray,
    ): Int = withContext(Dispatchers.IO) {
        if (position < 0) fail(WasiErrno.INVAL)
        if (bytes.isEmpty()) return@withContext 0
        nio { channel.write(ByteBuffer.wrap(bytes), position) }
    }

    override suspend fun setSize(size: Long) {
        withContext(Dispatchers.IO) {
            if (size < 0) fail(WasiErrno.INVAL)
            val currentSize = nio { channel.size() }
            when {
                size < currentSize -> nio { channel.truncate(size) }
                size > currentSize -> {
                    if (size == 0L) return@withContext
                    nio {
                        channel.write(
                            ByteBuffer.wrap(byteArrayOf(0)),
                            size - 1,
                        )
                    }
                }
            }
        }
    }

    override suspend fun sync(dataOnly: Boolean) {
        withContext(Dispatchers.IO) {
            nio { channel.force(!dataOnly) }
        }
    }

    override suspend fun setTimes(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos == null && modificationTimeNanos == null) return
        withContext(Dispatchers.IO) {
            val view = nio {
                Files.getFileAttributeView(
                    path,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } ?: fail(WasiErrno.NOTSUP)
            nio {
                view.setTimes(
                    modificationTimeNanos?.toFileTime(),
                    accessTimeNanos?.toFileTime(),
                    null,
                )
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            nio { channel.close() }
        }
    }
}

private fun canonicalPreopenRoot(root: Path): Path {
    val real =
        try {
            root.toRealPath()
        } catch (failure: IOException) {
            throw IllegalArgumentException("preopen root '$root' is not accessible", failure)
        } catch (failure: SecurityException) {
            throw IllegalArgumentException("preopen root '$root' is not accessible", failure)
        }
    require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
        "preopen root '$root' is not a directory"
    }
    return real
}

private inline fun <T> nio(operation: () -> T): T =
    try {
        operation()
    } catch (failure: WasiFileSystemException) {
        throw failure
    } catch (failure: NoSuchFileException) {
        fail(WasiErrno.NOENT, failure)
    } catch (failure: FileAlreadyExistsException) {
        fail(WasiErrno.EXIST, failure)
    } catch (failure: DirectoryNotEmptyException) {
        fail(WasiErrno.NOTEMPTY, failure)
    } catch (failure: AtomicMoveNotSupportedException) {
        fail(WasiErrno.NOTSUP, failure)
    } catch (failure: AccessDeniedException) {
        fail(WasiErrno.ACCES, failure)
    } catch (failure: NotDirectoryException) {
        fail(WasiErrno.NOTDIR, failure)
    } catch (failure: FileSystemLoopException) {
        fail(WasiErrno.LOOP, failure)
    } catch (failure: ClosedChannelException) {
        fail(WasiErrno.BADF, failure)
    } catch (failure: NonReadableChannelException) {
        fail(WasiErrno.BADF, failure)
    } catch (failure: NonWritableChannelException) {
        fail(WasiErrno.BADF, failure)
    } catch (failure: InvalidPathException) {
        fail(WasiErrno.INVAL, failure)
    } catch (failure: SecurityException) {
        fail(WasiErrno.ACCES, failure)
    } catch (failure: UnsupportedOperationException) {
        fail(WasiErrno.NOTSUP, failure)
    } catch (failure: FileSystemException) {
        fail(WasiErrno.IO, failure)
    } catch (failure: IOException) {
        fail(WasiErrno.IO, failure)
    }

private fun fail(
    errno: WasiErrno,
    cause: Throwable? = null,
): Nothing = throw WasiFileSystemException(errno, cause)
