@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kwasm.wasi

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun platformSecureRandomFill(destination: ByteArray) {
    if (destination.isEmpty()) return
    val status = destination.usePinned { pinned ->
        SecRandomCopyBytes(
            rnd = kSecRandomDefault,
            count = destination.size.toULong(),
            bytes = pinned.addressOf(0),
        )
    }
    if (status != errSecSuccess) {
        throw WasiRandomException("SecRandomCopyBytes failed with status $status")
    }
}
