package io.heapy.kwasm.gradle

import io.heapy.kwasm.ExperimentalKwasmApi
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for the kwasm host/guest build pipeline.
 *
 * A guest can be supplied either as an already-built [guestPath] or as a
 * [guestProject] whose Kotlin/Wasm executable task produces the input file.
 * When both are present, the project supplies the task dependency and the
 * explicit path selects the exact executable to embed.
 */
@ExperimentalKwasmApi
public open class KwasmExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout,
) {
    /**
     * Gradle path of the Kotlin/Wasm guest project, for example `:guest`.
     */
    @ExperimentalKwasmApi
    public val guestProject: Property<String> =
        objects.property(String::class.java)

    /**
     * The guest WebAssembly executable.
     *
     * This is optional when [guestProject] has exactly one `.wasm` output on
     * its selected executable compilation task.
     */
    @ExperimentalKwasmApi
    public val guestPath: RegularFileProperty =
        objects.fileProperty()

    /**
     * Task name in [guestProject] that builds [guestPath].
     *
     * If not set, kwasm discovers the conventional Kotlin/Wasm production (or
     * development when [devMode] is enabled) executable compilation task.
     */
    @ExperimentalKwasmApi
    public val guestCompileTask: Property<String> =
        objects.property(String::class.java)

    /**
     * Stable directory containing the Kotlin bindings collected from the KSP
     * outputs of the host and guest projects.
     *
     * Kotlin compilations consume KSP's target-specific output directories
     * directly. This aggregate is intended for tooling, inspection, and
     * publication without relying on KSP's internal directory layout.
     */
    @ExperimentalKwasmApi
    public val generatedBindingsDirectory: DirectoryProperty =
        objects.directoryProperty().convention(
            layout.buildDirectory.dir("generated/kwasm/bindings"),
        )

    /**
     * External dependency notation for the kwasm KSP processor.
     *
     * [bindgenProcessorClasspath] takes precedence when it is non-empty. The
     * plugin supplies coordinates matching its own version by default.
     */
    @ExperimentalKwasmApi
    public val bindgenProcessorDependency: Property<String> =
        objects.property(String::class.java)

    /**
     * Local processor files, useful for composite builds and plugin
     * development where the processor has not been published yet.
     */
    @ExperimentalKwasmApi
    public val bindgenProcessorClasspath: ConfigurableFileCollection =
        objects.fileCollection()

    /**
     * Root directory added to the host's main resources.
     */
    @ExperimentalKwasmApi
    public val embeddedResourcesDirectory: DirectoryProperty =
        objects.directoryProperty().convention(
            layout.buildDirectory.dir("generated/kwasm/resources"),
        )

    /**
     * Classpath-relative resource name of the embedded guest.
     */
    @ExperimentalKwasmApi
    public val embeddedResourcePath: Property<String> =
        objects.property(String::class.java).convention("kwasm/guest.wasm")

    /**
     * Generated resource file containing the guest executable.
     */
    @ExperimentalKwasmApi
    public val embeddedResourceOutput: RegularFileProperty =
        objects.fileProperty().convention(
            embeddedResourcesDirectory.zip(embeddedResourcePath) { directory, relativePath ->
                directory.file(relativePath)
            },
        )

    /**
     * Selects development guest outputs and enables the reload-manifest task.
     *
     * Run `kwasmDevReload --continuous` to let Gradle watch the guest build
     * inputs and refresh the manifest whenever the compiled module changes.
     * Gradle's `org.gradle.continuous.quietperiod` system property controls
     * event debounce for that invocation.
     */
    @ExperimentalKwasmApi
    public val devMode: Property<Boolean> =
        objects.property(Boolean::class.java).convention(false)

    /**
     * Deterministic manifest changed whenever the development guest changes.
     */
    @ExperimentalKwasmApi
    public val devReloadManifest: RegularFileProperty =
        objects.fileProperty().convention(
            layout.buildDirectory.file("generated/kwasm/reload/reload.properties"),
        )

    /** Alias spelling useful in convention plugins. */
    @ExperimentalKwasmApi
    public val guestProjectPath: Property<String>
        get() = guestProject

    /** Alias spelling that makes the input type explicit. */
    @ExperimentalKwasmApi
    public val guestWasm: RegularFileProperty
        get() = guestPath

    /** Alias spelling that makes the input type explicit. */
    @ExperimentalKwasmApi
    public val guestWasmFile: RegularFileProperty
        get() = guestPath

    /**
     * Configure the guest by Gradle project without retaining a [Project]
     * instance in the extension or task model.
     */
    @ExperimentalKwasmApi
    public fun guestProject(project: Project) {
        guestProject.set(project.path)
    }

    /** Configure the guest by Gradle project path. */
    @ExperimentalKwasmApi
    public fun guestProject(path: String) {
        guestProject.set(path)
    }
}
