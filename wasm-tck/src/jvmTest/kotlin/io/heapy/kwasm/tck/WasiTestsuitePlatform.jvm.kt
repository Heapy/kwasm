package io.heapy.kwasm.tck

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal object WasiTestsuitePlatform {
    fun configuredSuiteRoot(): String? =
        System.getProperty(SUITE_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: System.getenv(SUITE_ENVIRONMENT)?.takeIf(String::isNotBlank)

    fun loadPreview1Cases(root: String): List<WasiTestsuiteCase> {
        val suiteRoot = Path.of(root).toAbsolutePath().normalize()
        require(suiteRoot.isDirectory()) {
            "Configured wasi-testsuite root is not a directory: '$suiteRoot'"
        }
        val rootIsPreview1 = suiteRoot.fileName?.toString() == PREVIEW1_DIRECTORY
        return Files.walk(suiteRoot).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.fileName.toString().endsWith(".wasm") &&
                        (
                            rootIsPreview1 ||
                                suiteRoot.relativize(path).any { it.toString() == PREVIEW1_DIRECTORY }
                        )
                }
                .sorted()
                .map { wasm -> loadCase(suiteRoot, wasm) }
                .toList()
        }
    }

    fun checkedInExclusions(): String {
        val configured =
            System.getProperty(EXCLUSIONS_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?: System.getenv(EXCLUSIONS_ENVIRONMENT)?.takeIf(String::isNotBlank)
                ?: error("WASI exclusions path was not configured by Gradle")
        return Files.readString(Path.of(configured))
    }

    private fun loadCase(
        suiteRoot: Path,
        wasm: Path,
    ): WasiTestsuiteCase {
        val id = suiteRoot.relativize(wasm).invariantPath()
        val metadata = wasm.resolveSibling(
            wasm.fileName.toString().removeSuffix(".wasm") + ".json",
        )
        val spec = WasiTestsuiteSpec.parse(
            if (Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
                Files.readString(metadata)
            } else {
                null
            },
        )
        val preopen = spec.root?.let { root ->
            snapshotDirectory(wasm.parent.resolve(root).normalize())
        }
        return WasiTestsuiteCase(
            id = id,
            moduleBytes = Files.readAllBytes(wasm),
            spec = spec,
            preopen = preopen,
        )
    }

    private fun snapshotDirectory(root: Path): WasiTestsuitePreopenImage {
        require(!Files.isSymbolicLink(root)) {
            "WASI fixture preopen root must not be a symlink: '$root'"
        }
        require(root.isDirectory()) { "WASI fixture preopen is not a directory: '$root'" }
        val directories = mutableListOf<String>()
        val files = mutableListOf<WasiTestsuiteFile>()
        Files.walk(root).use { paths ->
            paths.sorted().forEach { path ->
                if (path == root) return@forEach
                require(!Files.isSymbolicLink(path)) {
                    "WASI fixture preopen contains a symlink, which is not deterministic: '$path'"
                }
                val relative = root.relativize(path).invariantPath()
                when {
                    path.isDirectory() -> directories += relative
                    path.isRegularFile() && !relative.endsWith(".cleanup") ->
                        files += WasiTestsuiteFile(relative, Files.readAllBytes(path))
                    path.isRegularFile() -> Unit
                    else -> error("WASI fixture preopen contains a special file: '$path'")
                }
            }
        }
        return WasiTestsuitePreopenImage(directories, files)
    }
}

private const val PREVIEW1_DIRECTORY: String = "wasm32-wasip1"
private const val SUITE_PROPERTY: String = "kwasm.wasi.testsuite.dir"
private const val SUITE_ENVIRONMENT: String = "KWASM_WASI_TESTSUITE_DIR"
private const val EXCLUSIONS_PROPERTY: String = "kwasm.wasi.testsuite.exclusions"
private const val EXCLUSIONS_ENVIRONMENT: String = "KWASM_WASI_TESTSUITE_EXCLUSIONS"

private fun Path.invariantPath(): String =
    joinToString("/") { it.toString() }
