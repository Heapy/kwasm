package io.heapy.kwasm.bindgen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasmGeneratedBinaryContractTest {
    @Test
    fun linkedKotlinWasmGuestHasCompilerImportsAndFourRuntimeExports() {
        val binaries = checkNotNull(
            System.getProperty("kwasm.bindgen.contract.binaries"),
        ) {
            "kwasm.bindgen.contract.binaries was not configured"
        }.split(File.pathSeparator).map { File(it).readBytes() }

        assertEquals(2, binaries.size, "expected wasmJs and wasmWasi linked modules")
        binaries.forEach { binary ->
            assertTrue(
                binary.containsSubsequence(
                    wasmName(WasmGuestRuntimeAbi.HOST_IMPORT_MODULE) +
                        wasmName(WasmGuestRuntimeAbi.HOST_BEGIN_IMPORT),
                ),
                "linked module is missing the begin host import",
            )
            assertTrue(
                binary.containsSubsequence(
                    wasmName(WasmGuestRuntimeAbi.HOST_IMPORT_MODULE) +
                        wasmName(WasmGuestRuntimeAbi.HOST_FINISH_IMPORT),
                ),
                "linked module is missing the finish host import",
            )

            val exports = binary.exports()
            val transportExports = exports.keys.filterTo(mutableSetOf()) {
                it == WasmGuestRuntimeAbi.MEMORY_EXPORT ||
                    it == WasmGuestRuntimeAbi.ALLOCATE_EXPORT ||
                    it == WasmGuestRuntimeAbi.FREE_EXPORT ||
                    it == WasmGuestRuntimeAbi.INVOKE_EXPORT
            }
            assertEquals(
                setOf(
                    WasmGuestRuntimeAbi.MEMORY_EXPORT,
                    WasmGuestRuntimeAbi.ALLOCATE_EXPORT,
                    WasmGuestRuntimeAbi.FREE_EXPORT,
                    WasmGuestRuntimeAbi.INVOKE_EXPORT,
                ),
                transportExports,
            )
            assertEquals(2, exports.getValue(WasmGuestRuntimeAbi.MEMORY_EXPORT))
            assertEquals(0, exports.getValue(WasmGuestRuntimeAbi.ALLOCATE_EXPORT))
            assertEquals(0, exports.getValue(WasmGuestRuntimeAbi.FREE_EXPORT))
            assertEquals(0, exports.getValue(WasmGuestRuntimeAbi.INVOKE_EXPORT))
        }
    }
}

private fun ByteArray.exports(): Map<String, Int> {
    require(size >= 8)
    require(copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0x61, 0x73, 0x6D))) {
        "not a WebAssembly binary"
    }
    var offset = 8
    while (offset < size) {
        val sectionId = this[offset++].toInt() and 0xFF
        val sectionSize = readUnsignedLeb(offset)
        offset = sectionSize.nextOffset
        val sectionEnd = offset + sectionSize.value
        require(sectionEnd in offset..size) { "invalid section size" }
        if (sectionId == 7) {
            val count = readUnsignedLeb(offset)
            offset = count.nextOffset
            return buildMap {
                repeat(count.value) {
                    val name = readWasmName(offset)
                    offset = name.nextOffset
                    require(offset < sectionEnd) { "truncated export descriptor" }
                    val kind = this@exports[offset++].toInt() and 0xFF
                    put(name.value, kind)
                    offset = readUnsignedLeb(offset).nextOffset // external index
                }
                require(offset == sectionEnd) { "trailing bytes in export section" }
            }
        }
        offset = sectionEnd
    }
    error("WebAssembly binary has no export section")
}

private fun ByteArray.readWasmName(offset: Int): Parsed<String> {
    val length = readUnsignedLeb(offset)
    val end = length.nextOffset + length.value
    require(end <= size) { "truncated WebAssembly name" }
    return Parsed(
        value = decodeToString(
            startIndex = length.nextOffset,
            endIndex = end,
            throwOnInvalidSequence = true,
        ),
        nextOffset = end,
    )
}

private fun ByteArray.readUnsignedLeb(offset: Int): Parsed<Int> {
    var cursor = offset
    var result = 0
    var shift = 0
    while (true) {
        require(cursor < size && shift < 35) { "invalid unsigned LEB128" }
        val byte = this[cursor++].toInt() and 0xFF
        result = result or ((byte and 0x7F) shl shift)
        if (byte and 0x80 == 0) return Parsed(result, cursor)
        shift += 7
    }
}

private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean {
    if (expected.isEmpty()) return true
    for (start in 0..size - expected.size) {
        var matches = true
        for (index in expected.indices) {
            if (this[start + index] != expected[index]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

private fun wasmName(value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    require(bytes.size < 128) { "test helper only supports short names" }
    return byteArrayOf(bytes.size.toByte()) + bytes
}

private data class Parsed<T>(
    val value: T,
    val nextOffset: Int,
)
