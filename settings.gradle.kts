pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kwasm"

include(
    ":annotations",
    ":benchmarks",
    ":bindgen-api",
    ":bindgen-ksp",
    ":bindgen-runtime",
    ":core",
    ":footprint-baseline",
    ":footprint-core",
    ":gradle-plugin",
    ":samples-cli",
    ":snapshot",
    ":tck",
    ":test-support-wat",
    ":wasi",
)

project(":annotations").projectDir = file("wasm-annotations")
project(":benchmarks").projectDir = file("benchmarks")
project(":bindgen-api").projectDir = file("wasm-bindgen-api")
project(":bindgen-ksp").projectDir = file("wasm-bindgen-ksp")
project(":bindgen-runtime").projectDir = file("wasm-bindgen-runtime")
project(":core").projectDir = file("wasm-core")
project(":footprint-baseline").projectDir = file("benchmarks/footprint/baseline")
project(":footprint-core").projectDir = file("benchmarks/footprint/core")
project(":gradle-plugin").projectDir = file("wasm-gradle-plugin")
project(":samples-cli").projectDir = file("wasm-cli")
project(":snapshot").projectDir = file("wasm-snapshot")
project(":tck").projectDir = file("wasm-tck")
project(":test-support-wat").projectDir = file("wasm-wat")
project(":wasi").projectDir = file("wasm-wasi")
