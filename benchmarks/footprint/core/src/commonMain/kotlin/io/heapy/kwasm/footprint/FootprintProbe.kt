@file:OptIn(
    io.heapy.kwasm.ExperimentalKwasmApi::class,
    ExperimentalObjCName::class,
)

package io.heapy.kwasm.footprint

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.Value
import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

@ObjCName("KwasmFootprintCoreProbe", exact = true)
public class FootprintProbe {
    public fun run(): Int = runBlocking {
        val module = Module.decode(WASM_VALUE_42)
        val result = Instance(module).invoke("value").single()
        (result as Value.I32).v
    }

    private companion object {
        val WASM_VALUE_42: ByteArray = byteArrayOf(
            0x00,
            0x61,
            0x73,
            0x6D,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x05,
            0x01,
            0x60,
            0x00,
            0x01,
            0x7F,
            0x03,
            0x02,
            0x01,
            0x00,
            0x07,
            0x09,
            0x01,
            0x05,
            0x76,
            0x61,
            0x6C,
            0x75,
            0x65,
            0x00,
            0x00,
            0x0A,
            0x06,
            0x01,
            0x04,
            0x00,
            0x41,
            0x2A,
            0x0B,
        )
    }
}
