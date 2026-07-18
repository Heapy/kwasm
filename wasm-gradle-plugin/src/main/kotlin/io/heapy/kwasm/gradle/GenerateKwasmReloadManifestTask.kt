package io.heapy.kwasm.gradle

import io.heapy.kwasm.ExperimentalKwasmApi
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/**
 * Produces the content-addressed handoff used by development reload loops.
 *
 * Gradle already supplies a portable file watcher. Running this task with
 * `--continuous` rebuilds the guest graph and rewrites [reloadManifest] after
 * the embedded module's content changes, without a plugin-owned daemon thread.
 */
@CacheableTask
@ExperimentalKwasmApi
public abstract class GenerateKwasmReloadManifestTask : DefaultTask() {
    /**
     * Directly tracked so Gradle continuous mode watches the linked guest even
     * though manifest bytes are calculated from the already-published resource.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @ExperimentalKwasmApi
    public abstract val linkedGuest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @ExperimentalKwasmApi
    public abstract val embeddedGuest: RegularFileProperty

    @get:Input
    @ExperimentalKwasmApi
    public abstract val embeddedResourcePath: Property<String>

    @get:Input
    @ExperimentalKwasmApi
    public abstract val devMode: Property<Boolean>

    @get:OutputFile
    @ExperimentalKwasmApi
    public abstract val reloadManifest: RegularFileProperty

    @TaskAction
    @ExperimentalKwasmApi
    public fun generateManifest() {
        if (!devMode.get()) {
            throw GradleException(
                "kwasmDevReload requires kwasm.devMode=true",
            )
        }

        val resourcePath = embeddedResourcePath.get()
        KwasmReloadFiles.validateResourcePath(resourcePath)
        val guest = embeddedGuest.get().asFile.toPath()
        val output = reloadManifest.get().asFile.toPath()
        val content = KwasmReloadFiles.manifestContent(
            embeddedResourcePath = resourcePath,
            size = Files.size(guest),
            sha256 = KwasmReloadFiles.sha256(guest),
        )
        KwasmReloadFiles.writeManifestAtomically(output, content)
    }
}
