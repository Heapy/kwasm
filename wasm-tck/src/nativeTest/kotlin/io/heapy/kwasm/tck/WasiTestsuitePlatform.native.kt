@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kwasm.tck

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.DT_DIR
import platform.posix.DT_LNK
import platform.posix.DT_REG
import platform.posix.EINVAL
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.opendir
import platform.posix.posix_errno
import platform.posix.readdir
import platform.posix.readlink
import platform.posix.rewind
import platform.posix.SEEK_END

internal object WasiTestsuitePlatform {
    fun configuredSuiteRoot(): String? =
        getenv(SUITE_ENVIRONMENT)?.toKString()?.takeIf(String::isNotBlank)

    fun loadPreview1Cases(root: String): List<WasiTestsuiteCase> {
        val suiteRoot = root.trimEnd('/')
        require(suiteRoot.isNotEmpty()) { "Configured wasi-testsuite root must not be empty" }
        val rootIsPreview1 = suiteRoot.substringAfterLast('/') == PREVIEW1_DIRECTORY
        val wasmFiles = walkDirectory(suiteRoot)
            .filter { entry ->
                !entry.directory &&
                    !entry.special &&
                    entry.relative.endsWith(".wasm") &&
                    (
                        rootIsPreview1 ||
                            entry.relative.split('/').contains(PREVIEW1_DIRECTORY)
                    )
            }
            .sortedBy(NativePathEntry::relative)
        return wasmFiles.map { entry -> loadCase(suiteRoot, entry.relative) }
    }

    fun checkedInExclusions(): String {
        val path = getenv(EXCLUSIONS_ENVIRONMENT)?.toKString()?.takeIf(String::isNotBlank)
            ?: error("WASI exclusions path was not configured by Gradle")
        return readBytes(path).decodeToString(throwOnInvalidSequence = true)
    }

    private fun loadCase(
        suiteRoot: String,
        relativeWasm: String,
    ): WasiTestsuiteCase {
        val wasm = joinPath(suiteRoot, relativeWasm)
        val metadata = wasm.removeSuffix(".wasm") + ".json"
        val spec = WasiTestsuiteSpec.parse(
            if (regularFileExists(metadata)) {
                readBytes(metadata).decodeToString(throwOnInvalidSequence = true)
            } else {
                null
            },
        )
        val preopen = spec.root?.let { declaredRoot ->
            val caseDirectory = relativeWasm.substringBeforeLast('/', missingDelimiterValue = "")
            val relativeRoot =
                if (caseDirectory.isEmpty()) declaredRoot
                else "$caseDirectory/$declaredRoot"
            snapshotDirectory(joinPath(suiteRoot, relativeRoot))
        }
        return WasiTestsuiteCase(
            id = relativeWasm,
            moduleBytes = readBytes(wasm),
            spec = spec,
            preopen = preopen,
        )
    }

    private fun snapshotDirectory(root: String): WasiTestsuitePreopenImage {
        requireNotSymlink(root)
        val entries = walkDirectory(root)
        val special = entries.firstOrNull { it.special }
        require(special == null) {
            "WASI fixture preopen contains a symlink or special file: " +
                joinPath(root, special?.relative.orEmpty())
        }
        return WasiTestsuitePreopenImage(
            directories = entries.filter(NativePathEntry::directory).map(NativePathEntry::relative),
            files = entries
                .filter { !it.directory && !it.relative.endsWith(".cleanup") }
                .map { entry ->
                    WasiTestsuiteFile(entry.relative, readBytes(joinPath(root, entry.relative)))
                },
        )
    }
}

private fun requireNotSymlink(path: String) {
    val byte = ByteArray(1)
    val count = byte.usePinned { pinned ->
        readlink(path, pinned.addressOf(0), 1uL)
    }
    require(count < 0 && posix_errno() == EINVAL) {
        "WASI fixture preopen root must be an accessible, non-symlink directory: '$path'"
    }
}

private data class NativePathEntry(
    val relative: String,
    val directory: Boolean,
    val special: Boolean = false,
)

private fun walkDirectory(root: String): List<NativePathEntry> {
    val result = mutableListOf<NativePathEntry>()
    walkDirectory(root, "", result)
    return result
}

private fun walkDirectory(
    root: String,
    relativeDirectory: String,
    result: MutableList<NativePathEntry>,
) {
    val absoluteDirectory =
        if (relativeDirectory.isEmpty()) root else joinPath(root, relativeDirectory)
    val directory = opendir(absoluteDirectory)
        ?: error("Cannot open directory '$absoluteDirectory' (errno ${posix_errno()})")
    try {
        while (true) {
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val relative =
                if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
            when (entry.pointed.d_type.toInt()) {
                DT_DIR -> {
                    result += NativePathEntry(relative, directory = true)
                    walkDirectory(root, relative, result)
                }
                DT_REG -> result += NativePathEntry(relative, directory = false)
                DT_LNK -> result += NativePathEntry(relative, directory = false, special = true)
                else -> result += NativePathEntry(relative, directory = false, special = true)
            }
        }
    } finally {
        closedir(directory)
    }
}

private fun regularFileExists(path: String): Boolean {
    val file = fopen(path, "rb") ?: return false
    fclose(file)
    return true
}

private fun readBytes(path: String): ByteArray {
    val file = fopen(path, "rb")
        ?: error("Cannot open file '$path' (errno ${posix_errno()})")
    try {
        check(fseek(file, 0, SEEK_END) == 0) {
            "Cannot seek file '$path' (errno ${posix_errno()})"
        }
        val length = ftell(file)
        check(length >= 0 && length <= Int.MAX_VALUE) {
            "File '$path' has unsupported size $length"
        }
        rewind(file)
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) {
            val count = bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
            check(count == bytes.size.toULong()) {
                "Short read for '$path': expected ${bytes.size}, got $count"
            }
        }
        return bytes
    } finally {
        fclose(file)
    }
}

private fun joinPath(
    parent: String,
    child: String,
): String = "${parent.trimEnd('/')}/${child.trimStart('/')}"

private const val PREVIEW1_DIRECTORY: String = "wasm32-wasip1"
private const val SUITE_ENVIRONMENT: String = "KWASM_WASI_TESTSUITE_DIR"
private const val EXCLUSIONS_ENVIRONMENT: String = "KWASM_WASI_TESTSUITE_EXCLUSIONS"
