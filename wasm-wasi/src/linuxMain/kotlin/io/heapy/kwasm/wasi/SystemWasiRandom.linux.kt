@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kwasm.wasi

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.posix_errno
import platform.posix.read

internal actual fun platformSecureRandomFill(destination: ByteArray) {
    if (destination.isEmpty()) return
    val descriptor = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
    if (descriptor < 0) {
        throw WasiRandomException(
            "opening /dev/urandom failed with errno ${posix_errno()}",
        )
    }

    try {
        destination.usePinned { pinned ->
            var offset = 0
            while (offset < destination.size) {
                val count = read(
                    descriptor,
                    pinned.addressOf(offset),
                    (destination.size - offset).toULong(),
                )
                when {
                    count > 0 -> offset += count.toInt()
                    count < 0 && posix_errno() == EINTR -> Unit
                    count < 0 -> throw WasiRandomException(
                        "reading /dev/urandom failed with errno ${posix_errno()}",
                    )
                    else -> throw WasiRandomException(
                        "reading /dev/urandom reached an unexpected end of file",
                    )
                }
            }
        }
    } finally {
        close(descriptor)
    }
}
