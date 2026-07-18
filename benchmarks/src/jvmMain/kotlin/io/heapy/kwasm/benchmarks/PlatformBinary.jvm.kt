package io.heapy.kwasm.benchmarks

import java.io.File

internal actual object PlatformBinary {
    actual fun environment(name: String): String? =
        System.getProperty(name) ?: System.getenv(name)

    actual fun read(path: String): ByteArray = File(path).readBytes()
}
