package io.heapy.kwasm.wasi

import io.heapy.kwasm.HostSnapshotHooks
import io.heapy.kwasm.SnapshotFormatException
import io.heapy.kwasm.SnapshotStateException

/**
 * Host-supplied resource identity for portable WASI descriptor snapshots.
 *
 * The snapshot stores only the returned keys plus descriptor metadata. It
 * never serializes streams, directory capabilities, or file handles. During
 * restoration the host must explicitly authorize every key by returning a
 * resource of the requested type. The object passed to the snapshot codec
 * implements this interface alongside its `SnapshotHooks` interface.
 *
 * Rehydration restores descriptor metadata, not external-world contents:
 * reopening a file at the same key is a host policy decision, and the host is
 * responsible for ensuring it denotes the intended resource generation.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface WasiSnapshotResourceHooks : HostSnapshotHooks {
    public fun externalizeInput(fd: Int, input: WasiInput): ByteArray?
    public fun rehydrateInput(fd: Int, key: ByteArray): WasiInput?

    public fun externalizeOutput(fd: Int, output: WasiOutput): ByteArray?
    public fun rehydrateOutput(fd: Int, key: ByteArray): WasiOutput?

    public fun externalizeDirectory(fd: Int, directory: WasiDirectory): ByteArray?
    public fun rehydrateDirectory(fd: Int, key: ByteArray): WasiDirectory?

    public fun externalizeFile(fd: Int, file: WasiFileHandle): ByteArray?
    public fun rehydrateFile(fd: Int, key: ByteArray): WasiFileHandle?
}

internal enum class WasiSnapshotDescriptorKind(
    val code: Int,
    val displayName: String,
) {
    Input(0, "input"),
    Output(1, "output"),
    Directory(2, "directory"),
    File(3, "file"),
}

internal data class WasiSnapshotDescriptor(
    val fd: Int,
    val kind: WasiSnapshotDescriptorKind,
    val rightsBase: ULong,
    val rightsInheriting: ULong,
    val flags: Int,
    val position: Long,
    val preopenName: ByteArray?,
    val resourceKey: ByteArray,
)

internal object WasiSnapshotStateCodec {
    private const val VERSION: Int = 1
    private const val MAX_DESCRIPTORS: Int = 65_536
    private const val MAX_RESOURCE_KEY_BYTES: Int = 1 * 1024 * 1024
    private const val MAX_PREOPEN_NAME_BYTES: Int = 65_535
    private const val MAX_STATE_BYTES: Int = 64 * 1024 * 1024

    fun encode(descriptors: List<WasiSnapshotDescriptor>): ByteArray {
        if (descriptors.size > MAX_DESCRIPTORS) {
            throw SnapshotStateException(
                "WASI descriptor count ${descriptors.size} exceeds limit $MAX_DESCRIPTORS",
            )
        }
        val writer = Writer(MAX_STATE_BYTES)
        writer.u16(VERSION)
        writer.u32(descriptors.size)
        val seen = mutableSetOf<Int>()
        descriptors.sortedBy(WasiSnapshotDescriptor::fd).forEach { descriptor ->
            validateCapturedDescriptor(descriptor, seen)
            writer.u32(descriptor.fd)
            writer.u8(descriptor.kind.code)
            writer.u8(0)
            writer.u16(0)
            writer.u64(descriptor.rightsBase)
            writer.u64(descriptor.rightsInheriting)
            writer.u32(descriptor.flags)
            writer.i64(descriptor.position)
            val preopenName = descriptor.preopenName
            if (preopenName == null) {
                writer.i32(-1)
            } else {
                writer.u32(preopenName.size)
                writer.bytes(preopenName)
            }
            writer.u32(descriptor.resourceKey.size)
            writer.bytes(descriptor.resourceKey)
        }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): List<WasiSnapshotDescriptor> {
        if (bytes.size > MAX_STATE_BYTES) {
            throw SnapshotFormatException(
                "WASI descriptor state has ${bytes.size} bytes; limit is $MAX_STATE_BYTES",
            )
        }
        val reader = Reader(bytes)
        val version = reader.u16("WASI descriptor-state version")
        if (version != VERSION) {
            throw SnapshotFormatException(
                "unsupported WASI descriptor-state version $version",
            )
        }
        val count = reader.count("WASI descriptor", MAX_DESCRIPTORS, 40)
        val descriptors = ArrayList<WasiSnapshotDescriptor>(count)
        val seen = mutableSetOf<Int>()
        repeat(count) { index ->
            val fd = reader.int("WASI descriptor $index fd")
            val kindCode = reader.u8("WASI descriptor $fd kind")
            val kind = WasiSnapshotDescriptorKind.entries.getOrNull(kindCode)
                ?: throw SnapshotFormatException(
                    "WASI descriptor $fd has unknown kind $kindCode",
                )
            if (
                reader.u8("WASI descriptor $fd reserved byte") != 0 ||
                reader.u16("WASI descriptor $fd reserved field") != 0
            ) {
                throw SnapshotFormatException(
                    "WASI descriptor $fd has non-zero reserved fields",
                )
            }
            val rightsBase = reader.u64("WASI descriptor $fd base rights")
            val rightsInheriting = reader.u64("WASI descriptor $fd inheriting rights")
            val flags = reader.int("WASI descriptor $fd flags")
            val position = reader.i64("WASI descriptor $fd position")
            val preopenLength = reader.i32("WASI descriptor $fd preopen length")
            val preopenName =
                when {
                    preopenLength == -1 -> null
                    preopenLength < 0 -> throw SnapshotFormatException(
                        "WASI descriptor $fd has invalid preopen length $preopenLength",
                    )
                    preopenLength > MAX_PREOPEN_NAME_BYTES -> throw SnapshotFormatException(
                        "WASI descriptor $fd preopen name has $preopenLength bytes; " +
                            "limit is $MAX_PREOPEN_NAME_BYTES",
                    )
                    else -> reader.bytes(preopenLength, "WASI descriptor $fd preopen name")
                }
            val keyLength = reader.int("WASI descriptor $fd resource-key length")
            if (keyLength > MAX_RESOURCE_KEY_BYTES) {
                throw SnapshotFormatException(
                    "WASI descriptor $fd resource key has $keyLength bytes; " +
                        "limit is $MAX_RESOURCE_KEY_BYTES",
                )
            }
            val descriptor = WasiSnapshotDescriptor(
                fd = fd,
                kind = kind,
                rightsBase = rightsBase,
                rightsInheriting = rightsInheriting,
                flags = flags,
                position = position,
                preopenName = preopenName,
                resourceKey = reader.bytes(
                    keyLength,
                    "WASI descriptor $fd resource key",
                ),
            )
            validateDecodedDescriptor(descriptor, seen)
            descriptors += descriptor
        }
        reader.requireEof("WASI descriptor state")
        return descriptors
    }

    private fun validateCapturedDescriptor(
        descriptor: WasiSnapshotDescriptor,
        seen: MutableSet<Int>,
    ) {
        if (!seen.add(descriptor.fd)) {
            throw SnapshotStateException("duplicate WASI descriptor ${descriptor.fd}")
        }
        if (descriptor.fd < 0) {
            throw SnapshotStateException("WASI descriptor ${descriptor.fd} is negative")
        }
        if (descriptor.resourceKey.size > MAX_RESOURCE_KEY_BYTES) {
            throw SnapshotStateException(
                "WASI descriptor ${descriptor.fd} resource key has " +
                    "${descriptor.resourceKey.size} bytes; limit is $MAX_RESOURCE_KEY_BYTES",
            )
        }
        val preopenSize = descriptor.preopenName?.size ?: 0
        if (preopenSize > MAX_PREOPEN_NAME_BYTES) {
            throw SnapshotStateException(
                "WASI descriptor ${descriptor.fd} preopen name has $preopenSize bytes; " +
                    "limit is $MAX_PREOPEN_NAME_BYTES",
            )
        }
        validateDescriptorMetadata(descriptor) {
            throw SnapshotStateException(it)
        }
    }

    private fun validateDecodedDescriptor(
        descriptor: WasiSnapshotDescriptor,
        seen: MutableSet<Int>,
    ) {
        if (!seen.add(descriptor.fd)) {
            throw SnapshotFormatException("duplicate WASI descriptor ${descriptor.fd}")
        }
        validateDescriptorMetadata(descriptor) {
            throw SnapshotFormatException(it)
        }
    }

    private inline fun validateDescriptorMetadata(
        descriptor: WasiSnapshotDescriptor,
        fail: (String) -> Nothing,
    ) {
        val fd = descriptor.fd
        if (descriptor.flags and WasiFdFlags.ALL.inv() != 0) {
            fail("WASI descriptor $fd has unsupported flags ${descriptor.flags}")
        }
        when (descriptor.kind) {
            WasiSnapshotDescriptorKind.Input -> {
                if (
                    descriptor.rightsBase and STANDARD_INPUT_RIGHTS !=
                    descriptor.rightsBase ||
                    descriptor.rightsInheriting != 0uL ||
                    descriptor.position != 0L ||
                    descriptor.preopenName != null
                ) {
                    fail("WASI input descriptor $fd has invalid metadata")
                }
            }
            WasiSnapshotDescriptorKind.Output -> {
                if (
                    descriptor.rightsBase and STANDARD_OUTPUT_RIGHTS !=
                    descriptor.rightsBase ||
                    descriptor.rightsInheriting != 0uL ||
                    descriptor.position != 0L ||
                    descriptor.preopenName != null
                ) {
                    fail("WASI output descriptor $fd has invalid metadata")
                }
            }
            WasiSnapshotDescriptorKind.File -> {
                if (
                    descriptor.rightsBase and WasiRights.REGULAR_FILE_DEFAULT !=
                    descriptor.rightsBase
                ) {
                    fail("WASI file descriptor $fd has rights outside regular-file rights")
                }
                if (
                    descriptor.rightsInheriting != 0uL ||
                    descriptor.position < 0 ||
                    descriptor.preopenName != null
                ) {
                    fail("WASI file descriptor $fd has invalid metadata")
                }
            }
            WasiSnapshotDescriptorKind.Directory -> {
                if (
                    descriptor.rightsBase and WasiRights.DIRECTORY_DEFAULT !=
                    descriptor.rightsBase
                ) {
                    fail("WASI directory descriptor $fd has rights outside directory rights")
                }
                val allowedInheriting =
                    WasiRights.DIRECTORY_DEFAULT or WasiRights.REGULAR_FILE_DEFAULT
                if (
                    descriptor.rightsInheriting and allowedInheriting !=
                    descriptor.rightsInheriting ||
                    descriptor.position != 0L
                ) {
                    fail("WASI directory descriptor $fd has invalid metadata")
                }
            }
        }
    }

    private class Writer(
        private val maximumSize: Int,
    ) {
        private var buffer = ByteArray(minOf(256, maximumSize))
        private var size: Int = 0

        fun u8(value: Int) {
            require(value in 0..0xFF)
            ensure(1)
            buffer[size++] = value.toByte()
        }

        fun u16(value: Int) {
            require(value in 0..0xFFFF)
            ensure(2)
            repeat(2) { shift ->
                buffer[size++] = (value ushr (shift * 8)).toByte()
            }
        }

        fun u32(value: Int) {
            if (value < 0) {
                throw SnapshotStateException(
                    "cannot encode negative unsigned WASI snapshot value $value",
                )
            }
            i32(value)
        }

        fun i32(value: Int) {
            ensure(4)
            repeat(4) { shift ->
                buffer[size++] = (value ushr (shift * 8)).toByte()
            }
        }

        fun i64(value: Long): Unit = u64(value.toULong())

        fun u64(value: ULong) {
            ensure(8)
            repeat(8) { shift ->
                buffer[size++] = (value shr (shift * 8)).toByte()
            }
        }

        fun bytes(value: ByteArray) {
            ensure(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(additional: Int) {
            if (additional < 0 || size > maximumSize - additional) {
                throw SnapshotStateException(
                    "WASI descriptor state exceeds maximum size $maximumSize",
                )
            }
            val required = size + additional
            if (required <= buffer.size) return
            var capacity = maxOf(buffer.size, 1)
            while (capacity < required) {
                capacity = minOf(
                    maximumSize.toLong(),
                    maxOf(required.toLong(), capacity.toLong() * 2),
                ).toInt()
                if (capacity < required) {
                    throw SnapshotStateException(
                        "WASI descriptor state exceeds maximum size $maximumSize",
                    )
                }
            }
            buffer = buffer.copyOf(capacity)
        }
    }

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var position: Int = 0
        private val remaining: Int get() = bytes.size - position

        fun u8(label: String): Int {
            requireBytes(1, label)
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(label: String): Int {
            requireBytes(2, label)
            var value = 0
            repeat(2) { shift ->
                value = value or ((bytes[position++].toInt() and 0xFF) shl (shift * 8))
            }
            return value
        }

        fun u32(label: String): UInt {
            requireBytes(4, label)
            var value = 0u
            repeat(4) { shift ->
                value = value or ((bytes[position++].toUInt() and 0xFFu) shl (shift * 8))
            }
            return value
        }

        fun int(label: String): Int {
            val value = u32(label)
            if (value > Int.MAX_VALUE.toUInt()) {
                throw SnapshotFormatException("$label $value exceeds Int.MAX_VALUE")
            }
            return value.toInt()
        }

        fun i32(label: String): Int = u32(label).toInt()

        fun u64(label: String): ULong {
            requireBytes(8, label)
            var value = 0uL
            repeat(8) { shift ->
                value = value or
                    ((bytes[position++].toULong() and 0xFFuL) shl (shift * 8))
            }
            return value
        }

        fun i64(label: String): Long = u64(label).toLong()

        fun count(label: String, maximum: Int, minimumBytes: Int): Int {
            val count = int("$label count")
            if (count > maximum) {
                throw SnapshotFormatException(
                    "$label count $count exceeds limit $maximum",
                )
            }
            if (count.toLong() * minimumBytes > remaining.toLong()) {
                throw SnapshotFormatException(
                    "$label count $count cannot fit in remaining $remaining bytes",
                )
            }
            return count
        }

        fun bytes(length: Int, label: String): ByteArray {
            requireBytes(length, label)
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun requireEof(label: String) {
            if (remaining != 0) {
                throw SnapshotFormatException(
                    "$label has $remaining trailing bytes at byte $position",
                )
            }
        }

        private fun requireBytes(count: Int, label: String) {
            if (count < 0 || count > remaining) {
                throw SnapshotFormatException(
                    "$label requires $count bytes at byte $position; only $remaining remain",
                )
            }
        }
    }
}

internal val STANDARD_INPUT_RIGHTS: ULong =
    WasiRights.FD_READ or WasiRights.FD_FDSTAT_SET_FLAGS or
        WasiRights.FD_FILESTAT_GET or WasiRights.POLL_FD_READWRITE

internal val STANDARD_OUTPUT_RIGHTS: ULong =
    WasiRights.FD_WRITE or WasiRights.FD_FDSTAT_SET_FLAGS or
        WasiRights.FD_FILESTAT_GET or WasiRights.POLL_FD_READWRITE
