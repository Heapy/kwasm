package io.heapy.kwasm.gradle

import io.heapy.kwasm.ExperimentalKwasmApi
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * Collects target-specific KSP bindgen outputs into a stable source tree.
 *
 * KSP owns the directories consumed by each Kotlin compilation. This task
 * provides a layout-independent aggregate for tools and downstream builds.
 */
@CacheableTask
@ExperimentalKwasmApi
public abstract class PrepareKwasmBindingsTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @ExperimentalKwasmApi
    public abstract val generatedSourceDirectories: ConfigurableFileCollection

    @get:OutputDirectory
    @ExperimentalKwasmApi
    public abstract val generatedBindingsDirectory: DirectoryProperty

    @TaskAction
    @ExperimentalKwasmApi
    public fun collectBindings() {
        val outputDirectory = generatedBindingsDirectory.get().asFile.toPath()
        fileSystemOperations.delete { it.delete(outputDirectory) }
        Files.createDirectories(outputDirectory)

        val collected = linkedSetOf<String>()
        generatedSourceDirectories.files
            .asSequence()
            .map { it.toPath() }
            .filter(Files::exists)
            .sortedBy { it.toAbsolutePath().normalize().toString() }
            .forEach { sourceRoot ->
                collectRoot(sourceRoot, outputDirectory, collected)
            }

        val manifest = buildString {
            appendLine("kwasm-bindings-format=1")
            collected.sorted().forEach { appendLine("source=$it") }
        }
        Files.writeString(
            outputDirectory.resolve(BINDINGS_MANIFEST),
            manifest,
            Charsets.UTF_8,
        )
    }

    private fun collectRoot(
        sourceRoot: Path,
        outputDirectory: Path,
        collected: MutableSet<String>,
    ) {
        if (Files.isRegularFile(sourceRoot)) {
            if (sourceRoot.fileName.toString().endsWith(BINDING_FILE_SUFFIX)) {
                copyBinding(
                    source = sourceRoot,
                    relativePath = sourceRoot.fileName,
                    outputDirectory = outputDirectory,
                    collected = collected,
                )
            }
            return
        }

        Files.walk(sourceRoot).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(BINDING_FILE_SUFFIX) }
                .sorted()
                .forEach { source ->
                    copyBinding(
                        source = source,
                        relativePath = sourceRoot.relativize(source),
                        outputDirectory = outputDirectory,
                        collected = collected,
                    )
                }
        }
    }

    private fun copyBinding(
        source: Path,
        relativePath: Path,
        outputDirectory: Path,
        collected: MutableSet<String>,
    ) {
        val normalizedRelativePath = relativePath.normalize()
        if (normalizedRelativePath.isAbsolute || normalizedRelativePath.startsWith("..")) {
            throw GradleException("Unsafe kwasm generated source path: $relativePath")
        }
        val destination = outputDirectory.resolve(normalizedRelativePath)
        if (Files.exists(destination)) {
            if (Files.mismatch(source, destination) != -1L) {
                throw GradleException(
                    "Conflicting kwasm generated binding '$normalizedRelativePath' " +
                        "was produced by more than one KSP compilation",
                )
            }
        } else {
            Files.createDirectories(destination.parent)
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        collected += normalizedRelativePath.toString().replace('\\', '/')
    }

    private companion object {
        const val BINDING_FILE_SUFFIX: String = "WasmBindings.kt"
        const val BINDINGS_MANIFEST: String = ".kwasm-bindings"
    }
}
