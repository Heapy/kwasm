package io.heapy.kwasm.bindgen.runtime

import io.heapy.kwasm.Instance
import kotlinx.coroutines.runBlocking

/** JVM blocking bridge for generated non-suspending boundary functions. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object JvmRunBlockingWasmBridge : WasmBlockingBridge {
    override fun execute(block: suspend () -> ByteArray): ByteArray =
        runBlocking { block() }
}

/** Create the JVM adapter normally passed to a generated `*HostClient`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Instance.asBindgenHostInvoker(
    limits: KwasmBindgenRuntimeLimits = KwasmBindgenRuntimeLimits(),
): KwasmInstanceHostInvoker = KwasmInstanceHostInvoker(
    instance = this,
    blockingBridge = JvmRunBlockingWasmBridge,
    limits = limits,
)
