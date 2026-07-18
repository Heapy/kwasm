package io.heapy.kwasm.gradle

import io.heapy.kwasm.ExperimentalKwasmApi
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies a compiled guest module into the host's generated resource tree.
 *
 * The task deliberately does not preserve source timestamps or permissions:
 * its only output is the exact byte sequence of the input module at a stable
 * path, making it reproducible and safe to store in Gradle's build cache.
 */
@CacheableTask
@ExperimentalKwasmApi
public abstract class EmbedKwasmGuestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @ExperimentalKwasmApi
    public abstract val guestPath: RegularFileProperty

    @get:OutputFile
    @ExperimentalKwasmApi
    public abstract val embeddedResourceOutput: RegularFileProperty

    /**
     * Part of the task's cache key because it selects the guest build variant.
     */
    @get:Input
    @ExperimentalKwasmApi
    public abstract val devMode: Property<Boolean>

    /** Alias used by callers that prefer the artifact-oriented name. */
    @get:Internal
    @ExperimentalKwasmApi
    public val guestWasm: RegularFileProperty
        get() = guestPath

    /** Alias used by callers that prefer the artifact-oriented name. */
    @get:Internal
    @ExperimentalKwasmApi
    public val guestWasmFile: RegularFileProperty
        get() = guestPath

    @TaskAction
    @ExperimentalKwasmApi
    public fun embedGuest() {
        val input = guestPath.get().asFile.toPath()
        val output = embeddedResourceOutput.get().asFile.toPath()

        if (!input.fileName.toString().endsWith(".wasm", ignoreCase = true)) {
            throw GradleException("kwasm guest input must be a .wasm file: $input")
        }
        if (input.toAbsolutePath().normalize() == output.toAbsolutePath().normalize()) {
            throw GradleException("kwasm guest input and embedded resource output must be different files")
        }

        KwasmReloadFiles.copyAtomically(input, output)
    }
}
