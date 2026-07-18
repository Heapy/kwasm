@file:OptIn(ExperimentalUnsignedTypes::class)

package io.heapy.kwasm.snapshot

/** Small dependency-free SHA-256 used to bind snapshots to exact module bytes. */
internal fun sha256(input: ByteArray): ByteArray = Sha256().apply { update(input) }.finish()

private class Sha256 {
    private val state = uintArrayOf(
        0x6a09e667u,
        0xbb67ae85u,
        0x3c6ef372u,
        0xa54ff53au,
        0x510e527fu,
        0x9b05688cu,
        0x1f83d9abu,
        0x5be0cd19u,
    )
    private val block = ByteArray(64)
    private var blockSize = 0
    private var totalBytes = 0L

    fun update(input: ByteArray) {
        var offset = 0
        totalBytes += input.size.toLong()
        while (offset < input.size) {
            val copied = minOf(64 - blockSize, input.size - offset)
            input.copyInto(block, blockSize, offset, offset + copied)
            blockSize += copied
            offset += copied
            if (blockSize == 64) {
                compress(block)
                blockSize = 0
            }
        }
    }

    fun finish(): ByteArray {
        val bitLength = totalBytes.toULong() * 8uL
        block[blockSize++] = 0x80.toByte()
        if (blockSize > 56) {
            block.fill(0, blockSize, 64)
            compress(block)
            blockSize = 0
        }
        block.fill(0, blockSize, 56)
        repeat(8) { index ->
            block[56 + index] = (bitLength shr ((7 - index) * 8)).toByte()
        }
        compress(block)

        val output = ByteArray(32)
        state.forEachIndexed { index, word ->
            repeat(4) { byteIndex ->
                output[index * 4 + byteIndex] =
                    (word shr ((3 - byteIndex) * 8)).toByte()
            }
        }
        return output
    }

    private fun compress(bytes: ByteArray) {
        val words = UIntArray(64)
        repeat(16) { index ->
            val offset = index * 4
            words[index] =
                ((bytes[offset].toUInt() and 0xFFu) shl 24) or
                    ((bytes[offset + 1].toUInt() and 0xFFu) shl 16) or
                    ((bytes[offset + 2].toUInt() and 0xFFu) shl 8) or
                    (bytes[offset + 3].toUInt() and 0xFFu)
        }
        for (index in 16 until 64) {
            val x = words[index - 15]
            val y = words[index - 2]
            val small0 = rotateRight(x, 7) xor rotateRight(x, 18) xor (x shr 3)
            val small1 = rotateRight(y, 17) xor rotateRight(y, 19) xor (y shr 10)
            words[index] = words[index - 16] + small0 + words[index - 7] + small1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        repeat(64) { index ->
            val big1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val first = h + big1 + choose + roundConstants[index] + words[index]
            val big0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val second = big0 + majority
            h = g
            g = f
            f = e
            e = d + first
            d = c
            c = b
            b = a
            a = first + second
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun rotateRight(value: UInt, distance: Int): UInt =
        (value shr distance) or (value shl (32 - distance))
}

private val roundConstants: UIntArray = uintArrayOf(
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
    0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
    0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
    0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
    0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
    0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
)
