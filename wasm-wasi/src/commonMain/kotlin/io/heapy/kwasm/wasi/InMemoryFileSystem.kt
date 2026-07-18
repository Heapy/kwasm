package io.heapy.kwasm.wasi

/**
 * A capability-friendly in-memory filesystem.
 *
 * Symbolic links are stored verbatim, but link traversal is anchored to the
 * granted directory and rejects absolute targets or `..`, so a link can never
 * escape the capability.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class InMemoryFileSystem {
    private var nextInode: ULong = 1u
    private var nextTimestampNanos: ULong = 1_700_000_000_000_000_000uL
    private val rootNode: DirectoryNode = newDirectoryNode()

    /** Capability for the filesystem root. */
    public val root: InMemoryDirectory = InMemoryDirectory(this, rootNode)

    /** Create a directory and any missing parents. */
    public fun createDirectories(path: String): InMemoryDirectory {
        val components = hostComponents(path)
        var directory = rootNode
        for (component in components) {
            val child = directory.children[component]
            directory =
                when (child) {
                    null -> newDirectoryNode().also { directory.children[component] = it }
                    is DirectoryNode -> child
                    is FileNode,
                    is SymbolicLinkNode,
                    -> throw IllegalArgumentException("'$component' is not a directory")
                }
        }
        return InMemoryDirectory(this, directory)
    }

    /** Return a capability for an existing directory. */
    public fun directory(path: String): InMemoryDirectory {
        val node = resolveHost(path)
        require(node is DirectoryNode) { "'$path' is not a directory" }
        return InMemoryDirectory(this, node)
    }

    /** Create or replace a file, optionally creating its parent directories. */
    public fun writeFile(
        path: String,
        bytes: ByteArray,
        createParents: Boolean = true,
    ) {
        val components = hostComponents(path)
        require(components.isNotEmpty()) { "file path must not name the root" }
        val parent =
            if (createParents) {
                createDirectories(components.dropLast(1).joinToString("/")).node
            } else {
                val parentPath = components.dropLast(1).joinToString("/")
                val node = resolveHost(parentPath)
                require(node is DirectoryNode) { "'$parentPath' is not a directory" }
                node
            }
        val name = components.last()
        require(parent.children[name] !is DirectoryNode) { "'$path' is a directory" }
        parent.children[name]?.decrementLinkCount()
        parent.children[name] = newFileNode(bytes.copyOf())
        parent.markChanged(this)
    }

    /** Read an existing file using content-copy semantics. */
    public fun readFile(path: String): ByteArray {
        val node = resolveHost(path)
        require(node is FileNode) { "'$path' is not a file" }
        return node.contents.copyOf()
    }

    /** True when a node exists at [path]. */
    public fun exists(path: String): Boolean =
        try {
            resolveHost(path)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun resolveHost(path: String): FsNode {
        var node: FsNode = rootNode
        for (component in hostComponents(path)) {
            val directory = node as? DirectoryNode
                ?: throw IllegalArgumentException("'$component' traverses a non-directory")
            node = directory.children[component]
                ?: throw IllegalArgumentException("'$path' does not exist")
        }
        return node
    }

    private fun hostComponents(path: String): List<String> {
        require('\u0000' !in path) { "path must not contain NUL" }
        return path
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .also { components ->
                require(components.none { it == ".." }) { "path must not contain '..'" }
            }
    }

    internal fun newDirectoryNode(): DirectoryNode =
        DirectoryNode(newInode(), newTimestamp())

    internal fun newFileNode(contents: ByteArray): FileNode =
        FileNode(contents, newInode(), newTimestamp())

    internal fun newSymbolicLinkNode(target: String): SymbolicLinkNode =
        SymbolicLinkNode(target, newInode(), newTimestamp())

    internal fun newTimestamp(): ULong {
        val timestamp = nextTimestampNanos
        check(timestamp != 0uL) { "in-memory timestamp space exhausted" }
        nextTimestampNanos++
        return timestamp
    }

    private fun newInode(): ULong {
        val inode = nextInode
        check(inode != 0uL) { "in-memory inode space exhausted" }
        nextInode++
        return inode
    }
}

/** An unforgeable directory capability within an [InMemoryFileSystem]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class InMemoryDirectory internal constructor(
    internal val fileSystem: InMemoryFileSystem,
    internal val node: DirectoryNode,
) : WasiDirectory {
    override suspend fun open(
        path: String,
        options: WasiPathOpenOptions,
    ): WasiOpenedResource = openInMemory(path, options)

    override suspend fun statSelf(): WasiFileStat =
        pathStat(node).attributes

    override suspend fun setTimesSelf(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        updateTimes(node, accessTimeNanos, modificationTimeNanos)
    }

    override suspend fun readEntries(): List<WasiDirectoryEntry> =
        node.children.map { (name, child) ->
            val stat = pathStat(child)
            WasiDirectoryEntry(
                name = name,
                fileType = stat.fileType,
                inode = stat.attributes.inode,
            )
        }.sortedBy { it.name }

    override suspend fun createDirectory(path: String) {
        val (parent, name) = resolveParent(path)
        if (parent.children.containsKey(name)) {
            throw WasiFileSystemException(WasiErrno.EXIST)
        }
        parent.children[name] = fileSystem.newDirectoryNode()
        parent.markChanged(fileSystem)
    }

    override suspend fun stat(
        path: String,
        followSymlinks: Boolean,
    ): WasiPathStat =
        pathStat(resolvePath(path, followFinalSymlink = followSymlinks))

    override suspend fun setTimes(
        path: String,
        followSymlinks: Boolean,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        val target = resolvePath(path, followFinalSymlink = followSymlinks)
        updateTimes(target, accessTimeNanos, modificationTimeNanos)
    }

    private fun updateTimes(
        target: FsNode,
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos != null) target.accessTimeNanos = accessTimeNanos
        if (modificationTimeNanos != null) {
            target.modificationTimeNanos = modificationTimeNanos
        }
        if (accessTimeNanos != null || modificationTimeNanos != null) {
            target.changeTimeNanos = fileSystem.newTimestamp()
        }
    }

    override suspend fun link(
        sourcePath: String,
        followSourceSymlink: Boolean,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        val target = targetDirectory as? InMemoryDirectory
            ?: throw WasiFileSystemException(WasiErrno.NOTSUP)
        if (target.fileSystem !== fileSystem) {
            throw WasiFileSystemException(WasiErrno.NOTSUP)
        }
        target.rejectTrailingCreationTarget(targetPath)
        val source = resolvePath(sourcePath, followFinalSymlink = followSourceSymlink)
        if (source is DirectoryNode) throw WasiFileSystemException(WasiErrno.PERM)
        val (targetParent, targetName) = target.resolveParent(targetPath)
        if (targetParent.children.containsKey(targetName)) {
            throw WasiFileSystemException(WasiErrno.EXIST)
        }
        source.incrementLinkCount()
        targetParent.children[targetName] = source
        source.changeTimeNanos = fileSystem.newTimestamp()
        targetParent.markChanged(fileSystem)
    }

    override suspend fun readLink(path: String): ByteArray {
        val link = resolvePath(path, followFinalSymlink = false) as? SymbolicLinkNode
            ?: throw WasiFileSystemException(WasiErrno.INVAL)
        return link.target.encodeToByteArray()
    }

    override suspend fun removeDirectory(path: String) {
        val (parent, name) = resolveParent(path)
        val directory = parent.children[name]
            ?: throw WasiFileSystemException(WasiErrno.NOENT)
        if (directory !is DirectoryNode) throw WasiFileSystemException(WasiErrno.NOTDIR)
        if (directory.children.isNotEmpty()) throw WasiFileSystemException(WasiErrno.NOTEMPTY)
        parent.children.remove(name)
        parent.markChanged(fileSystem)
    }

    override suspend fun rename(
        sourcePath: String,
        targetDirectory: WasiDirectory,
        targetPath: String,
    ) {
        val target = targetDirectory as? InMemoryDirectory
            ?: throw WasiFileSystemException(WasiErrno.NOTSUP)
        if (target.fileSystem !== fileSystem) {
            throw WasiFileSystemException(WasiErrno.NOTSUP)
        }
        val (sourceParent, sourceName) = resolveParent(sourcePath)
        val source = sourceParent.children[sourceName]
            ?: throw WasiFileSystemException(WasiErrno.NOENT)
        val (targetParent, targetName) = target.resolveParent(targetPath)
        if (sourceParent === targetParent && sourceName == targetName) return
        if (source is DirectoryNode && source.containsDirectory(targetParent)) {
            throw WasiFileSystemException(WasiErrno.INVAL)
        }
        val existing = targetParent.children[targetName]
        if (existing === source) return
        when (existing) {
            null -> Unit
            is DirectoryNode -> {
                if (source !is DirectoryNode) throw WasiFileSystemException(WasiErrno.ISDIR)
                if (existing.children.isNotEmpty()) {
                    throw WasiFileSystemException(WasiErrno.NOTEMPTY)
                }
            }
            is FileNode,
            is SymbolicLinkNode,
            -> if (source is DirectoryNode) throw WasiFileSystemException(WasiErrno.NOTDIR)
        }
        existing?.decrementLinkCount()
        targetParent.children[targetName] = source
        sourceParent.children.remove(sourceName)
        val changed = fileSystem.newTimestamp()
        source.changeTimeNanos = changed
        sourceParent.modificationTimeNanos = changed
        sourceParent.changeTimeNanos = changed
        targetParent.modificationTimeNanos = changed
        targetParent.changeTimeNanos = changed
    }

    override suspend fun createSymbolicLink(
        targetPath: String,
        linkPath: String,
    ) {
        requireRelativeSymlinkTarget(targetPath)
        rejectTrailingCreationTarget(linkPath)
        val (parent, name) = resolveParent(linkPath)
        if (parent.children.containsKey(name)) {
            throw WasiFileSystemException(WasiErrno.EXIST)
        }
        parent.children[name] = fileSystem.newSymbolicLinkNode(targetPath)
        parent.markChanged(fileSystem)
    }

    override suspend fun unlinkFile(path: String) {
        rejectTrailingUnlinkTarget(path)
        val (parent, name) = resolveParent(path)
        val target = parent.children[name]
            ?: throw WasiFileSystemException(WasiErrno.NOENT)
        if (target is DirectoryNode) throw WasiFileSystemException(WasiErrno.ISDIR)
        target.decrementLinkCount()
        parent.children.remove(name)
        target.changeTimeNanos = fileSystem.newTimestamp()
        parent.markChanged(fileSystem)
    }
}

internal sealed interface FsNode {
    val inode: ULong
    var accessTimeNanos: ULong
    var modificationTimeNanos: ULong
    var changeTimeNanos: ULong
}

internal class DirectoryNode(
    override val inode: ULong,
    createdAtNanos: ULong,
) : FsNode {
    val children: MutableMap<String, FsNode> = linkedMapOf()
    override var accessTimeNanos: ULong = createdAtNanos
    override var modificationTimeNanos: ULong = createdAtNanos
    override var changeTimeNanos: ULong = createdAtNanos
}

internal class FileNode(
    var contents: ByteArray,
    override val inode: ULong,
    createdAtNanos: ULong,
) : FsNode {
    var linkCount: ULong = 1u
    override var accessTimeNanos: ULong = createdAtNanos
    override var modificationTimeNanos: ULong = createdAtNanos
    override var changeTimeNanos: ULong = createdAtNanos
}

internal class SymbolicLinkNode(
    val target: String,
    override val inode: ULong,
    createdAtNanos: ULong,
) : FsNode {
    var linkCount: ULong = 1u
    override var accessTimeNanos: ULong = createdAtNanos
    override var modificationTimeNanos: ULong = createdAtNanos
    override var changeTimeNanos: ULong = createdAtNanos
}

private fun InMemoryDirectory.openInMemory(
    rawPath: String,
    options: WasiPathOpenOptions,
): WasiOpenedResource {
    val components = guestComponents(rawPath)
    if (rawPath.isEmpty()) throw WasiFileSystemException(WasiErrno.NOENT)
    if (components.isEmpty()) {
        if (options.create && options.exclusive) {
            throw WasiFileSystemException(WasiErrno.EXIST)
        }
        if (options.truncate) {
            throw WasiFileSystemException(WasiErrno.ISDIR)
        }
        return WasiOpenedDirectory(this)
    }

    val parent = resolveDirectory(components.dropLast(1))

    val name = components.last()
    val existing = parent.children[name]
    if (existing == null) {
        if (!options.create) throw WasiFileSystemException(WasiErrno.NOENT)
        if (options.requireDirectory) throw WasiFileSystemException(WasiErrno.NOENT)
        val file = fileSystem.newFileNode(ByteArray(0)).also { parent.children[name] = it }
        parent.markChanged(fileSystem)
        return WasiOpenedFile(InMemoryFileHandle(fileSystem, file))
    }
    if (options.create && options.exclusive) {
        throw WasiFileSystemException(WasiErrno.EXIST)
    }
    val opened =
        if (existing is SymbolicLinkNode) {
            if (!options.followSymlinks) throw WasiFileSystemException(WasiErrno.LOOP)
            resolvePath(rawPath, followFinalSymlink = true)
        } else {
            existing
        }
    if (options.requireDirectory && opened !is DirectoryNode) {
        throw WasiFileSystemException(WasiErrno.NOTDIR)
    }
    if (options.truncate) {
        val file = opened as? FileNode
            ?: throw WasiFileSystemException(WasiErrno.ISDIR)
        file.contents = ByteArray(0)
    }
    return when (opened) {
        is FileNode -> WasiOpenedFile(InMemoryFileHandle(fileSystem, opened))
        is DirectoryNode ->
            WasiOpenedDirectory(InMemoryDirectory(fileSystem, opened))
        is SymbolicLinkNode -> error("final symbolic link must have been resolved")
    }
}

private fun guestComponents(path: String): List<String> {
    return guestPathComponents(path)
}

private fun InMemoryDirectory.resolveParent(rawPath: String): Pair<DirectoryNode, String> {
    val components = guestComponents(rawPath)
    if (components.isEmpty()) throw WasiFileSystemException(WasiErrno.NOENT)
    return resolveDirectory(components.dropLast(1)) to components.last()
}

private fun InMemoryDirectory.rejectTrailingCreationTarget(rawPath: String) {
    val components = guestPathComponents(rawPath)
    if (!rawPath.endsWith('/')) return
    val existing =
        try {
            resolveComponents(node, components, followFinalSymlink = true)
        } catch (failure: WasiFileSystemException) {
            if (failure.errno == WasiErrno.NOENT) throw failure
            throw failure
        }
    throw WasiFileSystemException(
        if (existing is DirectoryNode) WasiErrno.EXIST else WasiErrno.NOTDIR,
    )
}

private fun InMemoryDirectory.rejectTrailingUnlinkTarget(rawPath: String) {
    val components = guestPathComponents(rawPath)
    if (!rawPath.endsWith('/')) return
    val existing = resolveComponents(node, components, followFinalSymlink = true)
    throw WasiFileSystemException(
        if (existing is DirectoryNode) WasiErrno.ISDIR else WasiErrno.NOTDIR,
    )
}

private fun InMemoryDirectory.resolveDirectory(components: List<String>): DirectoryNode {
    if (components.isEmpty()) return node
    return resolveComponents(
        start = node,
        components = components,
        followFinalSymlink = true,
    ) as? DirectoryNode ?: throw WasiFileSystemException(WasiErrno.NOTDIR)
}

private fun InMemoryDirectory.resolvePath(
    rawPath: String,
    followFinalSymlink: Boolean,
): FsNode {
    val components = guestComponents(rawPath)
    if (components.isEmpty()) throw WasiFileSystemException(WasiErrno.NOENT)
    return resolveComponents(node, components, followFinalSymlink)
}

private fun resolveComponents(
    start: DirectoryNode,
    components: List<String>,
    followFinalSymlink: Boolean,
    followedLinks: Int = 0,
): FsNode {
    if (followedLinks >= MAX_IN_MEMORY_SYMLINKS) {
        throw WasiFileSystemException(WasiErrno.LOOP)
    }
    var directory = start
    val directoryPath = mutableListOf<String>()
    components.forEachIndexed { index, component ->
        val child = directory.children[component]
            ?: throw WasiFileSystemException(WasiErrno.NOENT)
        val last = index == components.lastIndex
        when (child) {
            is DirectoryNode -> {
                if (last) return child
                directory = child
                directoryPath += component
            }
            is FileNode -> {
                if (!last) throw WasiFileSystemException(WasiErrno.NOTDIR)
                return child
            }
            is SymbolicLinkNode -> {
                if (last && !followFinalSymlink) return child
                val target = resolveRelativeSymlinkComponents(
                    parentComponents = directoryPath,
                    targetPath = child.target,
                )
                if (target.isEmpty()) throw WasiFileSystemException(WasiErrno.NOENT)
                return resolveComponents(
                    start = start,
                    components = target + components.drop(index + 1),
                    followFinalSymlink = followFinalSymlink,
                    followedLinks = followedLinks + 1,
                )
            }
        }
    }
    return directory
}

private fun resolveRelativeSymlinkComponents(
    parentComponents: List<String>,
    targetPath: String,
): List<String> {
    requireRelativeSymlinkTarget(targetPath)
    val resolved = parentComponents.toMutableList()
    targetPath.split('/').forEach { component ->
        when (component) {
            "", "." -> Unit
            ".." -> {
                if (resolved.isEmpty()) {
                    throw WasiFileSystemException(WasiErrno.NOTCAPABLE)
                }
                resolved.removeAt(resolved.lastIndex)
            }
            else -> resolved += component
        }
    }
    return resolved
}

private fun pathStat(node: FsNode): WasiPathStat =
    when (node) {
        is DirectoryNode ->
            WasiPathStat(
                fileType = WasiFileType.DIRECTORY,
                attributes = WasiFileStat(
                    inode = node.inode,
                    size = 0u,
                    accessTimeNanos = node.accessTimeNanos,
                    modificationTimeNanos = node.modificationTimeNanos,
                    changeTimeNanos = node.changeTimeNanos,
                ),
            )
        is FileNode ->
            WasiPathStat(
                fileType = WasiFileType.REGULAR_FILE,
                attributes = WasiFileStat(
                    inode = node.inode,
                    linkCount = node.linkCount,
                    size = node.contents.size.toULong(),
                    accessTimeNanos = node.accessTimeNanos,
                    modificationTimeNanos = node.modificationTimeNanos,
                    changeTimeNanos = node.changeTimeNanos,
                ),
            )
        is SymbolicLinkNode ->
            WasiPathStat(
                fileType = WasiFileType.SYMBOLIC_LINK,
                attributes = WasiFileStat(
                    inode = node.inode,
                    linkCount = node.linkCount,
                    size = node.target.encodeToByteArray().size.toULong(),
                    accessTimeNanos = node.accessTimeNanos,
                    modificationTimeNanos = node.modificationTimeNanos,
                    changeTimeNanos = node.changeTimeNanos,
                ),
            )
    }

private fun DirectoryNode.containsDirectory(candidate: DirectoryNode): Boolean {
    if (this === candidate) return true
    return children.values.any { child ->
        child is DirectoryNode && child.containsDirectory(candidate)
    }
}

private fun FsNode.incrementLinkCount() {
    when (this) {
        is FileNode -> linkCount++
        is SymbolicLinkNode -> linkCount++
        is DirectoryNode -> Unit
    }
}

private fun FsNode.decrementLinkCount() {
    when (this) {
        is FileNode -> linkCount--
        is SymbolicLinkNode -> linkCount--
        is DirectoryNode -> Unit
    }
}

private fun DirectoryNode.markChanged(fileSystem: InMemoryFileSystem) {
    val changed = fileSystem.newTimestamp()
    modificationTimeNanos = changed
    changeTimeNanos = changed
}

private const val MAX_IN_MEMORY_SYMLINKS: Int = 40

private class InMemoryFileHandle(
    private val fileSystem: InMemoryFileSystem,
    private val node: FileNode,
) : WasiFileHandle {
    override suspend fun size(): Long = node.contents.size.toLong()

    override suspend fun stat(): WasiFileStat =
        WasiFileStat(
            inode = node.inode,
            linkCount = node.linkCount,
            size = node.contents.size.toULong(),
            accessTimeNanos = node.accessTimeNanos,
            modificationTimeNanos = node.modificationTimeNanos,
            changeTimeNanos = node.changeTimeNanos,
        )

    override suspend fun read(
        position: Long,
        maximumBytes: Int,
    ): ByteArray {
        if (position < 0 || maximumBytes < 0) {
            throw WasiFileSystemException(WasiErrno.INVAL)
        }
        if (position >= node.contents.size) return ByteArray(0)
        val start = position.toInt()
        val count = minOf(maximumBytes, node.contents.size - start)
        return node.contents.copyOfRange(start, start + count)
    }

    override suspend fun write(
        position: Long,
        bytes: ByteArray,
    ): Int {
        if (position < 0) throw WasiFileSystemException(WasiErrno.INVAL)
        if (position > Int.MAX_VALUE || bytes.size > Int.MAX_VALUE - position.toInt()) {
            throw WasiFileSystemException(WasiErrno.FBIG)
        }
        val end = position.toInt() + bytes.size
        if (end > node.contents.size) node.contents = node.contents.copyOf(end)
        bytes.copyInto(node.contents, position.toInt())
        val changed = fileSystem.newTimestamp()
        node.modificationTimeNanos = changed
        node.changeTimeNanos = changed
        return bytes.size
    }

    override suspend fun setSize(size: Long) {
        if (size < 0) throw WasiFileSystemException(WasiErrno.INVAL)
        if (size > Int.MAX_VALUE) throw WasiFileSystemException(WasiErrno.FBIG)
        node.contents = node.contents.copyOf(size.toInt())
        val changed = fileSystem.newTimestamp()
        node.modificationTimeNanos = changed
        node.changeTimeNanos = changed
    }

    override suspend fun sync(dataOnly: Boolean) = Unit

    override suspend fun setTimes(
        accessTimeNanos: ULong?,
        modificationTimeNanos: ULong?,
    ) {
        if (accessTimeNanos != null) node.accessTimeNanos = accessTimeNanos
        if (modificationTimeNanos != null) {
            node.modificationTimeNanos = modificationTimeNanos
        }
        node.changeTimeNanos = fileSystem.newTimestamp()
    }

    override suspend fun close() = Unit
}
