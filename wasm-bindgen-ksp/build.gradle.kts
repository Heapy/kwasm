plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    explicitApiWarning()
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.ksp.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.ksp.api)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "kwasm-bindgen-ksp"
            pom {
                name = "kwasm bindgen KSP processor"
                description = "KSP code generator for kwasm shared boundary interfaces"
                url = "https://github.com/heapy/kwasm"
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}
