@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kwasm.benchmarks

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.rewind

internal actual object PlatformBinary {
    actual fun environment(name: String): String? = getenv(name)?.toKString()

    actual fun read(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("cannot open benchmark module '$path'")
        try {
            check(fseek(file, 0, SEEK_END) == 0) { "cannot seek benchmark module '$path'" }
            val size = ftell(file)
            check(size >= 0) { "cannot determine benchmark module size for '$path'" }
            rewind(file)
            val bytes = ByteArray(size.toInt())
            if (bytes.isNotEmpty()) {
                val read = bytes.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1u, bytes.size.convert(), file)
                }
                check(read.toLong() == bytes.size.toLong()) {
                    "short read for benchmark module '$path': $read/${bytes.size}"
                }
            }
            return bytes
        } finally {
            fclose(file)
        }
    }
}
