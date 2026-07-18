plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }
}

sourceSets {
    main {
        kotlin.srcDir("src")
    }
    test {
        kotlin.srcDir("test")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":test-support-wat"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.heapy.kwasm.cli.SmokeTestKt"
}
