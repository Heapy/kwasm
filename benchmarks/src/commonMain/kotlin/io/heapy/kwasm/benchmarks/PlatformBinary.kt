package io.heapy.kwasm.benchmarks

internal expect object PlatformBinary {
    fun environment(name: String): String?
    fun read(path: String): ByteArray
}
