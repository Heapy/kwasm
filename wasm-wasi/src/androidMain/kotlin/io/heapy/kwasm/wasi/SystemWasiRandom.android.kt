package io.heapy.kwasm.wasi

import java.security.SecureRandom

private val systemSecureRandom: SecureRandom = SecureRandom()

internal actual fun platformSecureRandomFill(destination: ByteArray) {
    try {
        systemSecureRandom.nextBytes(destination)
    } catch (failure: RuntimeException) {
        throw WasiRandomException("the host secure random source failed", failure)
    }
}
