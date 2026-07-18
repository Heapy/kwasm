plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    explicitApiWarning()
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }
}

dependencies {
    api(project(":annotations"))
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

val bindgenProcessorJar =
    project(":bindgen-ksp").tasks.named<Jar>("jar").flatMap { it.archiveFile }

tasks.test {
    dependsOn(bindgenProcessorJar)
    systemProperty(
        "kwasm.test.bindgenProcessorJar",
        bindgenProcessorJar.get().asFile.absolutePath,
    )
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version
}

gradlePlugin {
    plugins {
        create("kwasm") {
            id = "io.heapy.kwasm"
            implementationClass = "io.heapy.kwasm.gradle.KwasmPlugin"
            displayName = "kwasm Gradle plugin"
            description = "Compiles Kotlin/Wasm guests, generates bindings, and embeds guest modules."
        }
    }
}
