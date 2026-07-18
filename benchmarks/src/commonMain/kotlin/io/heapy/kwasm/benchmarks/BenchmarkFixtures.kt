package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.CheckpointMode
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.Value
import io.heapy.kwasm.wat.WatComposer

internal object BenchmarkFixtures {
    const val JSON_DOCUMENT: String =
        """{"project":"kwasm","active":true,"revision":42,"tags":["kotlin","wasm","coroutines"],"workers":[{"id":1,"name":"alpha","scores":[3,5,8,13,21]},{"id":2,"name":"beta","scores":[34,55,89,144]}],"metadata":{"owner":"runtime-team","region":"eu","retries":4}}"""

    val fibModule: ByteArray by lazy {
        WatComposer.compose(
            """
            (module
              (func (export "fib") (param i32) (result i32)
                (block
                  local.get 0
                  i32.const 2
                  i32.lt_s
                  br_if 0
                  local.get 0
                  i32.const 1
                  i32.sub
                  call 0
                  local.get 0
                  i32.const 2
                  i32.sub
                  call 0
                  i32.add
                  return)
                local.get 0))
            """.trimIndent(),
        )
    }

    val sha256LoopModule: ByteArray by lazy {
        WatComposer.compose(
            """
            (module
              (func (export "sha256_loop") (param i32 i32) (result i32)
                (local i32 i32)
                local.get 1
                local.set 2
                i32.const 0
                local.set 3
                (block
                  (loop
                    local.get 3
                    local.get 0
                    i32.ge_u
                    br_if 1

                    local.get 2
                    i32.const 2
                    i32.rotr
                    local.get 2
                    i32.const 13
                    i32.rotr
                    i32.xor
                    local.get 2
                    i32.const 22
                    i32.rotr
                    i32.xor
                    local.get 2
                    i32.const 6
                    i32.rotr
                    local.get 2
                    i32.const 11
                    i32.rotr
                    i32.xor
                    local.get 2
                    i32.const 25
                    i32.rotr
                    i32.xor
                    i32.add
                    local.get 3
                    i32.const 1116352408
                    i32.add
                    i32.add
                    local.set 2

                    local.get 3
                    i32.const 1
                    i32.add
                    local.set 3
                    br 0))
                local.get 2))
            """.trimIndent(),
        )
    }

    val jsonModule: ByteArray by lazy {
        val data = encodeWatData(JSON_DOCUMENT)
        WatComposer.compose(
            """
            (module
              (memory 1)
              (data (i32.const 0) "$data")
              (func (export "parse_json") (param i32) (result i32)
                (local i32 i32 i32 i32 i32)
                i32.const 0
                local.set 1
                i32.const 0
                local.set 2
                i32.const 0
                local.set 3
                i32.const 0
                local.set 4
                (block
                  (loop
                    local.get 1
                    local.get 0
                    i32.ge_u
                    br_if 1

                    local.get 1
                    i32.load8_u
                    local.set 5

                    local.get 4
                    i32.const 31
                    i32.mul
                    local.get 5
                    i32.xor
                    local.set 4

                    local.get 2
                    local.get 5
                    i32.const 123
                    i32.eq
                    local.get 5
                    i32.const 91
                    i32.eq
                    i32.or
                    i32.add
                    local.set 2

                    local.get 2
                    local.get 5
                    i32.const 125
                    i32.eq
                    local.get 5
                    i32.const 93
                    i32.eq
                    i32.or
                    i32.sub
                    local.set 2

                    local.get 3
                    local.get 5
                    i32.const 34
                    i32.eq
                    i32.add
                    local.set 3

                    local.get 1
                    i32.const 1
                    i32.add
                    local.set 1
                    br 0))
                local.get 4
                local.get 2
                i32.xor
                local.get 3
                i32.xor))
            """.trimIndent(),
        )
    }

    val hostToGuestModule: ByteArray by lazy {
        WatComposer.compose(
            """
            (module
              (func (export "roundtrip") (param i32) (result i32)
                local.get 0))
            """.trimIndent(),
        )
    }

    val guestToHostModule: ByteArray by lazy {
        WatComposer.compose(
            """
            (module
              (import "benchmark" "roundtrip" (func (param i32) (result i32)))
              (func (export "roundtrip") (param i32) (result i32)
                local.get 0
                call 0))
            """.trimIndent(),
        )
    }

    val snapshot64MiBModule: ByteArray by lazy {
        WatComposer.compose(
            """
            (module
              (memory 1024 1024)
              (func (export "spin")
                (loop
                  br 0)))
            """.trimIndent(),
        )
    }

    fun instance(
        bytes: ByteArray,
        checkpointMode: CheckpointMode = CheckpointMode.Enabled,
    ): Instance {
        val module = Module.decode(bytes)
        return Instance(
            Store(StoreConfig(checkpointMode = checkpointMode)),
            module,
            ResolvedImports(),
        )
    }

    suspend fun invokeI32(
        instance: Instance,
        export: String,
        vararg arguments: Int,
    ): Int {
        val results = instance.invoke(export, arguments.map { Value.I32(it) })
        check(results.size == 1) { "$export returned ${results.size} values" }
        return (results.single() as Value.I32).v
    }

    fun exactSizeModule(sizeBytes: Int): ByteArray {
        require(sizeBytes >= 16) { "module size must leave room for a custom section" }
        val magic = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        var payloadSize = sizeBytes - magic.size - 2
        while (true) {
            val sectionBodySize = 1 + payloadSize
            val encodedSectionSize = encodeU32(sectionBodySize)
            val totalSize = magic.size + 1 + encodedSectionSize.size + sectionBodySize
            val correction = sizeBytes - totalSize
            if (correction == 0) {
                return ByteArray(sizeBytes).also { module ->
                    magic.copyInto(module)
                    var cursor = magic.size
                    module[cursor++] = 0
                    encodedSectionSize.copyInto(module, cursor)
                    cursor += encodedSectionSize.size
                    module[cursor] = 0
                }
            }
            payloadSize += correction
            require(payloadSize >= 0) { "module size is too small" }
        }
    }

    private fun encodeWatData(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            when (val unsigned = byte.toInt() and 0xFF) {
                0x22 -> append("\\22")
                0x5C -> error("benchmark JSON deliberately contains no backslash")
                in 0x20..0x7E -> append(unsigned.toChar())
                else -> error("benchmark JSON must remain printable ASCII")
            }
        }
    }

    private fun encodeU32(value: Int): ByteArray {
        require(value >= 0)
        val encoded = ArrayList<Byte>(5)
        var remaining = value
        do {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) byte = byte or 0x80
            encoded += byte.toByte()
        } while (remaining != 0)
        return encoded.toByteArray()
    }
}
