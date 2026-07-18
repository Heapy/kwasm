package io.heapy.kwasm.gradle

import com.google.devtools.ksp.gradle.KspAATask
import io.heapy.kwasm.ExperimentalKwasmApi
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

@ExperimentalKwasmApi
public const val KWASM_EXTENSION_NAME: String = "kwasm"

@ExperimentalKwasmApi
public const val PREPARE_KWASM_BINDINGS_TASK_NAME: String = "prepareKwasmBindings"

@ExperimentalKwasmApi
public const val EMBED_KWASM_GUEST_TASK_NAME: String = "embedKwasmGuest"

@ExperimentalKwasmApi
public const val KWASM_DEV_RELOAD_TASK_NAME: String = "kwasmDevReload"

@ExperimentalKwasmApi
public const val ASSEMBLE_KWASM_TASK_NAME: String = "assembleKwasm"

/**
 * Wires the deterministic pieces of the kwasm host/guest build pipeline.
 */
@ExperimentalKwasmApi
public class KwasmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            KWASM_EXTENSION_NAME,
            KwasmExtension::class.java,
        )
        extension.bindgenProcessorDependency.convention(
            "io.heapy.kwasm:kwasm-bindgen-ksp:${pluginVersion()}",
        )

        val prepareBindings = project.tasks.register(
            PREPARE_KWASM_BINDINGS_TASK_NAME,
            PrepareKwasmBindingsTask::class.java,
        ) {
            it.group = KWASM_TASK_GROUP
            it.description = "Prepares the generated kwasm bindings directory."
            it.generatedBindingsDirectory.convention(extension.generatedBindingsDirectory)
        }

        val embedGuest = project.tasks.register(
            EMBED_KWASM_GUEST_TASK_NAME,
            EmbedKwasmGuestTask::class.java,
        ) {
            it.group = KWASM_TASK_GROUP
            it.description = "Embeds the compiled Kotlin/Wasm guest as a host resource."
            it.guestPath.convention(extension.guestPath)
            it.embeddedResourceOutput.convention(extension.embeddedResourceOutput)
            it.devMode.convention(extension.devMode)
        }

        val devReload = project.tasks.register(
            KWASM_DEV_RELOAD_TASK_NAME,
            GenerateKwasmReloadManifestTask::class.java,
        ) {
            it.group = KWASM_TASK_GROUP
            it.description =
                "Builds the development guest and updates its reload manifest; " +
                    "run with --continuous to watch for changes."
            it.linkedGuest.convention(extension.guestPath)
            it.embeddedGuest.set(embedGuest.flatMap(EmbedKwasmGuestTask::embeddedResourceOutput))
            it.embeddedResourcePath.convention(extension.embeddedResourcePath)
            it.devMode.convention(extension.devMode)
            it.reloadManifest.convention(extension.devReloadManifest)
            it.dependsOn(embedGuest)
            it.onlyIf("kwasm.devMode is enabled") { task ->
                (task as GenerateKwasmReloadManifestTask).devMode.get()
            }
        }

        project.tasks.register(ASSEMBLE_KWASM_TASK_NAME) {
            it.group = KWASM_TASK_GROUP
            it.description = "Prepares bindings and embeds the kwasm guest module."
            it.dependsOn(prepareBindings, embedGuest, devReload)
        }

        configureHostResources(project, extension, embedGuest)
        registerBindgenProject(project, extension, prepareBindings)

        /*
         * A root host is evaluated before its subprojects, so registering the
         * guest hook here still lets KSP attach while the guest's Kotlin
         * plugin is being applied. The projectsEvaluated callback below only
         * resolves the executable task after every project has registered it.
         */
        project.afterEvaluate {
            val guestProjectPath = extension.guestProject.orNull
            if (guestProjectPath != null) {
                val guestProject = project.rootProject.findProject(guestProjectPath)
                    ?: throw GradleException(
                        "kwasm guest project '$guestProjectPath' does not exist in " +
                            project.rootProject.path,
                    )
                if (guestProject != project) {
                    registerBindgenProject(guestProject, extension, prepareBindings)
                }
            }
        }

        project.gradle.projectsEvaluated {
            configureGuestProject(project, extension, embedGuest)
        }
    }

    private fun configureGuestProject(
        hostProject: Project,
        extension: KwasmExtension,
        embedGuest: TaskProvider<EmbedKwasmGuestTask>,
    ) {
        val guestProjectPath = extension.guestProject.orNull ?: return
        val guestProject = hostProject.rootProject.findProject(guestProjectPath)
            ?: throw GradleException(
                "kwasm guest project '$guestProjectPath' does not exist in " +
                    hostProject.rootProject.path,
            )
        val guestTask = findGuestCompileTask(guestProject, extension)

        if (guestTask == null) {
            if (!extension.guestPath.isPresent) {
                throw GradleException(
                    "No Kotlin/Wasm executable compilation task was found in " +
                        "'${guestProject.path}'. Configure kwasm.guestCompileTask " +
                        "or kwasm.guestPath explicitly.",
                )
            }
            return
        }

        embedGuest.configure { it.dependsOn(guestTask) }
        if (!extension.guestPath.isPresent) {
            extension.guestPath.set(singleWasmOutput(hostProject, guestTask))
        }
    }

    private fun registerBindgenProject(
        kotlinProject: Project,
        extension: KwasmExtension,
        prepareBindings: TaskProvider<PrepareKwasmBindingsTask>,
    ) {
        KOTLIN_PLUGIN_IDS.forEach { kotlinPluginId ->
            kotlinProject.pluginManager.withPlugin(kotlinPluginId) {
                val multiplatform =
                    kotlinProject.pluginManager.hasPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID)
                registerProcessorDependencies(kotlinProject, extension, multiplatform)
                kotlinProject.pluginManager.apply(KSP_PLUGIN_ID)
                val kspTasks = kotlinProject.tasks
                    .withType(KspAATask::class.java)
                    .matching { !it.name.contains("test", ignoreCase = true) }
                prepareBindings.configure { prepare ->
                    prepare.generatedSourceDirectories.from(
                        kotlinProject.files(kspTasks),
                    )
                    prepare.dependsOn(kspTasks)
                }
            }
        }
    }

    private fun registerProcessorDependencies(
        kotlinProject: Project,
        extension: KwasmExtension,
        multiplatform: Boolean,
    ) {
        kotlinProject.configurations.configureEach { configuration ->
            if (!isProductionKspConfiguration(configuration.name, multiplatform)) {
                return@configureEach
            }
            // A dependency must exist when KSP inspects the compilation in
            // order for it to register the task. The actual processor
            // dependency is attached after the owning DSL has been evaluated.
            configuration.dependencies.add(
                kotlinProject.dependencies.create(
                    kotlinProject.files(),
                ),
            )
        }

        kotlinProject.afterEvaluate {
            val localProcessorFiles = extension.bindgenProcessorClasspath.files
            val notation: Any =
                if (localProcessorFiles.isNotEmpty()) {
                    kotlinProject.files(localProcessorFiles)
                } else {
                    val coordinates = extension.bindgenProcessorDependency.orNull
                    if (coordinates.isNullOrBlank()) {
                        throw GradleException(
                            "kwasm.bindgenProcessorDependency must not be blank when " +
                                "kwasm.bindgenProcessorClasspath is empty",
                        )
                    }
                    coordinates
                }
            kotlinProject.configurations
                .matching {
                    isProductionKspConfiguration(it.name, multiplatform)
                }
                .configureEach { configuration ->
                    kotlinProject.dependencies.add(configuration.name, notation)
                }
        }
    }

    private fun configureHostResources(
        hostProject: Project,
        extension: KwasmExtension,
        embedGuest: TaskProvider<EmbedKwasmGuestTask>,
    ) {
        val generatedResources = hostProject.files(extension.embeddedResourcesDirectory)
            .builtBy(embedGuest)

        hostProject.pluginManager.withPlugin(KOTLIN_JVM_PLUGIN_ID) {
            hostProject.extensions
                .getByType(SourceSetContainer::class.java)
                .named("main") { sourceSet ->
                    sourceSet.resources.srcDir(generatedResources)
                }
        }
        hostProject.pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
            hostProject.extensions
                .getByType(KotlinMultiplatformExtension::class.java)
                .sourceSets
                .matching { it.name == "commonMain" }
                .configureEach { sourceSet ->
                    sourceSet.resources.srcDir(generatedResources)
                }
        }
    }

    private fun isProductionKspConfiguration(
        name: String,
        multiplatform: Boolean,
    ): Boolean {
        if (!multiplatform) return name == "ksp"
        if (name == "ksp" || !name.startsWith("ksp")) return false
        return !name.contains("test", ignoreCase = true) &&
            !name.contains("processorClasspath", ignoreCase = true) &&
            !name.contains("pluginClasspath", ignoreCase = true) &&
            !name.endsWith("Metadata")
    }

    private fun pluginVersion(): String =
        KwasmPlugin::class.java.`package`.implementationVersion
            ?: FALLBACK_PLUGIN_VERSION

    private fun findGuestCompileTask(
        guestProject: Project,
        extension: KwasmExtension,
    ): TaskProvider<Task>? {
        val explicitName = extension.guestCompileTask.orNull
        if (explicitName != null) {
            val simpleName = explicitName.substringAfterLast(':')
            if (simpleName.isBlank()) {
                throw GradleException(
                    "kwasm.guestCompileTask must name a task in '${guestProject.path}'",
                )
            }
            if (guestProject.tasks.findByName(simpleName) == null) {
                throw GradleException(
                    "kwasm guest compile task '${guestProject.path}:$simpleName' does not exist",
                )
            }
            return guestProject.tasks.named(simpleName)
        }

        val names = if (extension.devMode.get()) {
            DEVELOPMENT_GUEST_TASKS + PRODUCTION_GUEST_TASKS
        } else {
            PRODUCTION_GUEST_TASKS + DEVELOPMENT_GUEST_TASKS
        }
        val name = names.firstOrNull { guestProject.tasks.findByName(it) != null }
            ?: return null
        return guestProject.tasks.named(name)
    }

    private fun singleWasmOutput(
        hostProject: Project,
        guestTask: TaskProvider<Task>,
    ): Provider<RegularFile> =
        hostProject.layout.file(
            hostProject.providers.provider {
                val wasmOutputs = guestTask.get().outputs.files.files
                    .asSequence()
                    .flatMap { output ->
                        when {
                            output.extension.equals("wasm", ignoreCase = true) ->
                                sequenceOf(output)

                            output.isDirectory ->
                                output.walkTopDown()
                                    .filter { it.isFile && it.extension.equals("wasm", ignoreCase = true) }
                                    .asSequence()

                            else -> emptySequence()
                        }
                    }
                    .distinctBy { it.absoluteFile.normalize().path }
                    .sortedBy { it.absoluteFile.normalize().path }
                    .toList()

                when (wasmOutputs.size) {
                    1 -> wasmOutputs.single()
                    0 -> throw GradleException(
                        "kwasm guest compile task '${guestTask.get().path}' does not declare " +
                            "a .wasm output; configure kwasm.guestPath explicitly.",
                    )

                    else -> throw GradleException(
                        "kwasm guest compile task '${guestTask.get().path}' declares multiple " +
                            ".wasm outputs; configure kwasm.guestPath explicitly. Found: " +
                            wasmOutputs.joinToString(),
                    )
                }
            },
        )

    private companion object {
        const val KWASM_TASK_GROUP: String = "kwasm"
        const val KSP_PLUGIN_ID: String = "com.google.devtools.ksp"
        const val KOTLIN_JVM_PLUGIN_ID: String = "org.jetbrains.kotlin.jvm"
        const val KOTLIN_MULTIPLATFORM_PLUGIN_ID: String =
            "org.jetbrains.kotlin.multiplatform"
        const val FALLBACK_PLUGIN_VERSION: String = "0.1.0-SNAPSHOT"

        val KOTLIN_PLUGIN_IDS: List<String> = listOf(
            KOTLIN_JVM_PLUGIN_ID,
            KOTLIN_MULTIPLATFORM_PLUGIN_ID,
            "org.jetbrains.kotlin.android",
        )

        val PRODUCTION_GUEST_TASKS: List<String> = listOf(
            "wasmWasiProductionExecutableCompileSync",
            "wasmJsProductionExecutableCompileSync",
        )

        val DEVELOPMENT_GUEST_TASKS: List<String> = listOf(
            "wasmWasiDevelopmentExecutableCompileSync",
            "wasmJsDevelopmentExecutableCompileSync",
        )
    }

}
