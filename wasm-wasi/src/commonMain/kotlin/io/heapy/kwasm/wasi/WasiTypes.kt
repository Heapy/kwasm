package io.heapy.kwasm.wasi

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource

/** The legacy WASI Preview 1 import module name. */
@io.heapy.kwasm.ExperimentalKwasmApi
public const val WASI_SNAPSHOT_PREVIEW1: String = "wasi_snapshot_preview1"

/** Preview 1 errno values used by the host implementation. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class WasiErrno(public val code: Int) {
    SUCCESS(0),
    TOOBIG(1),
    ACCES(2),
    BADF(8),
    EXIST(20),
    FAULT(21),
    FBIG(22),
    ILSEQ(25),
    INVAL(28),
    IO(29),
    ISDIR(31),
    LOOP(32),
    MFILE(33),
    NAMETOOLONG(37),
    NOENT(44),
    NOMEM(48),
    NOSYS(52),
    NOTDIR(54),
    NOTEMPTY(55),
    NOTSOCK(57),
    NOTSUP(58),
    OVERFLOW(61),
    PERM(63),
    PIPE(64),
    ROFS(69),
    SPIPE(70),
    NOTCAPABLE(76),
}

/** WASI Preview 1 descriptor file types. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class WasiFileType(public val code: Int) {
    UNKNOWN(0),
    BLOCK_DEVICE(1),
    CHARACTER_DEVICE(2),
    DIRECTORY(3),
    REGULAR_FILE(4),
    SOCKET_DGRAM(5),
    SOCKET_STREAM(6),
    SYMBOLIC_LINK(7),
}

/** Bit constants from `__wasi_rights_t`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasiRights {
    public const val FD_DATASYNC: ULong = 0x0000_0001uL
    public const val FD_READ: ULong = 0x0000_0002uL
    public const val FD_SEEK: ULong = 0x0000_0004uL
    public const val FD_FDSTAT_SET_FLAGS: ULong = 0x0000_0008uL
    public const val FD_SYNC: ULong = 0x0000_0010uL
    public const val FD_TELL: ULong = 0x0000_0020uL
    public const val FD_WRITE: ULong = 0x0000_0040uL
    public const val FD_ADVISE: ULong = 0x0000_0080uL
    public const val FD_ALLOCATE: ULong = 0x0000_0100uL
    public const val PATH_CREATE_DIRECTORY: ULong = 0x0000_0200uL
    public const val PATH_CREATE_FILE: ULong = 0x0000_0400uL
    public const val PATH_LINK_SOURCE: ULong = 0x0000_0800uL
    public const val PATH_LINK_TARGET: ULong = 0x0000_1000uL
    public const val PATH_OPEN: ULong = 0x0000_2000uL
    public const val FD_READDIR: ULong = 0x0000_4000uL
    public const val PATH_READLINK: ULong = 0x0000_8000uL
    public const val PATH_RENAME_SOURCE: ULong = 0x0001_0000uL
    public const val PATH_RENAME_TARGET: ULong = 0x0002_0000uL
    public const val PATH_FILESTAT_GET: ULong = 0x0004_0000uL
    public const val PATH_FILESTAT_SET_SIZE: ULong = 0x0008_0000uL
    public const val PATH_FILESTAT_SET_TIMES: ULong = 0x0010_0000uL
    public const val FD_FILESTAT_GET: ULong = 0x0020_0000uL
    public const val FD_FILESTAT_SET_SIZE: ULong = 0x0040_0000uL
    public const val FD_FILESTAT_SET_TIMES: ULong = 0x0080_0000uL
    public const val PATH_SYMLINK: ULong = 0x0100_0000uL
    public const val PATH_REMOVE_DIRECTORY: ULong = 0x0200_0000uL
    public const val PATH_UNLINK_FILE: ULong = 0x0400_0000uL
    public const val POLL_FD_READWRITE: ULong = 0x0800_0000uL
    public const val SOCK_SHUTDOWN: ULong = 0x1000_0000uL
    public const val SOCK_ACCEPT: ULong = 0x2000_0000uL

    public const val REGULAR_FILE_DEFAULT: ULong = 0x08E0_01FFuL
    public const val DIRECTORY_DEFAULT: ULong = 0x0FBF_FE18uL
}

/** Bit constants from `__wasi_fdflags_t`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasiFdFlags {
    public const val APPEND: Int = 1
    public const val DSYNC: Int = 2
    public const val NONBLOCK: Int = 4
    public const val RSYNC: Int = 8
    public const val SYNC: Int = 16
    internal const val ALL: Int = APPEND or DSYNC or NONBLOCK or RSYNC or SYNC
}

/** Bit constants from `__wasi_oflags_t`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasiOpenFlags {
    public const val CREATE: Int = 1
    public const val DIRECTORY: Int = 2
    public const val EXCLUSIVE: Int = 4
    public const val TRUNCATE: Int = 8
    internal const val ALL: Int = CREATE or DIRECTORY or EXCLUSIVE or TRUNCATE
}

/** Bit constants from `__wasi_fstflags_t` for filestat timestamp mutation. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasiFileStatSetTimesFlags {
    public const val ACCESS_TIME: Int = 1
    public const val ACCESS_TIME_NOW: Int = 2
    public const val MODIFICATION_TIME: Int = 4
    public const val MODIFICATION_TIME_NOW: Int = 8
    internal const val ALL: Int =
        ACCESS_TIME or ACCESS_TIME_NOW or
            MODIFICATION_TIME or MODIFICATION_TIME_NOW
}

/** A stream used for descriptor 0 or another read-capable descriptor. */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface WasiInput {
    /**
     * Read at most [maximumBytes]. Returning an empty array means end of file.
     * Implementations may suspend without blocking the executing thread.
     */
    public suspend fun read(maximumBytes: Int): ByteArray
}

/** A stream used for descriptors 1/2 or another write-capable descriptor. */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface WasiOutput {
    /**
     * Write a prefix of [bytes] and return its size. Implementations may
     * suspend without blocking the executing thread.
     */
    public suspend fun write(bytes: ByteArray): Int
}

/** Byte-array-backed input useful for deterministic embedding and tests. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ByteArrayWasiInput(bytes: ByteArray) : WasiInput {
    private val contents: ByteArray = bytes.copyOf()
    private var position: Int = 0

    override suspend fun read(maximumBytes: Int): ByteArray {
        require(maximumBytes >= 0) { "maximumBytes must be non-negative" }
        if (maximumBytes == 0 || position == contents.size) return ByteArray(0)
        val count = minOf(maximumBytes, contents.size - position)
        return contents.copyOfRange(position, position + count).also { position += count }
    }
}

/** Output stream retaining all bytes in memory. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class BufferWasiOutput : WasiOutput {
    private var contents: ByteArray = ByteArray(0)

    public val size: Int get() = contents.size

    public fun bytes(): ByteArray = contents.copyOf()

    public fun text(): String = contents.decodeToString(throwOnInvalidSequence = true)

    public fun clear() {
        contents = ByteArray(0)
    }

    override suspend fun write(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val previousSize = contents.size
        contents = contents.copyOf(previousSize + bytes.size)
        bytes.copyInto(contents, previousSize)
        return bytes.size
    }
}

/** Clock source used by `clock_res_get` and `clock_time_get`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface WasiClock {
    /** Resolution in nanoseconds, or null when [clockId] is unsupported. */
    public fun resolutionNanos(clockId: Int): ULong?

    /** Current time in nanoseconds, or null when [clockId] is unsupported. */
    public fun timeNanos(clockId: Int, precisionNanos: ULong): ULong?
}

/**
 * Default host clock. Realtime is Unix epoch time and monotonic time starts at
 * creation of this object. CPU-time clock identifiers are not fabricated.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class SystemWasiClock : WasiClock {
    private val monotonicOrigin = TimeSource.Monotonic.markNow()

    override fun resolutionNanos(clockId: Int): ULong? =
        when (clockId) {
            0 -> 1_000_000uL
            1 -> 1uL
            else -> null
        }

    override fun timeNanos(clockId: Int, precisionNanos: ULong): ULong? =
        when (clockId) {
            0 -> {
                val millis = Clock.System.now().toEpochMilliseconds()
                if (millis < 0 || millis > Long.MAX_VALUE / 1_000_000L) null
                else (millis * 1_000_000L).toULong()
            }
            1 -> monotonicOrigin.elapsedNow().inWholeNanoseconds.coerceAtLeast(0).toULong()
            else -> null
        }
}

/** Random byte source used by `random_get`. */
@io.heapy.kwasm.ExperimentalKwasmApi
public fun interface WasiRandom {
    public suspend fun fill(destination: ByteArray)
}

/**
 * Cryptographically secure random source backed by the host operating system.
 *
 * JVM and Android use `java.security.SecureRandom`, Apple targets use
 * `SecRandomCopyBytes`, and Linux targets read from `/dev/urandom`.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public object SystemWasiRandom : WasiRandom {
    override suspend fun fill(destination: ByteArray) {
        platformSecureRandomFill(destination)
    }
}

/**
 * General-purpose Kotlin random source.
 *
 * This adapter is useful for deterministic tests when supplied with a seeded
 * [Random]. It is not cryptographically secure and is therefore not the
 * [WasiConfig] default.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class KotlinWasiRandom(
    private val random: Random = Random.Default,
) : WasiRandom {
    override suspend fun fill(destination: ByteArray) {
        random.nextBytes(destination)
    }
}

/** A deterministic clock convenient for reproducible executions and tests. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class FixedWasiClock(
    private val realtimeNanos: ULong = 0u,
    private val monotonicNanos: ULong = 0u,
    private val resolution: ULong = 1u,
) : WasiClock {
    override fun resolutionNanos(clockId: Int): ULong? =
        if (clockId == 0 || clockId == 1) resolution else null

    override fun timeNanos(clockId: Int, precisionNanos: ULong): ULong? =
        when (clockId) {
            0 -> realtimeNanos
            1 -> monotonicNanos
            else -> null
        }
}

/** One explicit directory capability exposed to the guest. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiPreopen(
    public val guestPath: String,
    public val directory: WasiDirectory,
    public val rightsBase: ULong = WasiRights.DIRECTORY_DEFAULT,
    public val rightsInheriting: ULong =
        WasiRights.DIRECTORY_DEFAULT or WasiRights.REGULAR_FILE_DEFAULT,
) {
    init {
        require(guestPath.isNotEmpty()) { "preopen guestPath must not be empty" }
        require('\u0000' !in guestPath) { "preopen guestPath must not contain NUL" }
    }
}

/** Configuration and explicit capabilities for one WASI process instance. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasiConfig(
    public val arguments: List<String> = emptyList(),
    public val environment: Map<String, String> = emptyMap(),
    public val preopens: List<WasiPreopen> = emptyList(),
    public val standardInput: WasiInput = ByteArrayWasiInput(ByteArray(0)),
    public val standardOutput: WasiOutput = BufferWasiOutput(),
    public val standardError: WasiOutput = BufferWasiOutput(),
    public val clock: WasiClock = SystemWasiClock(),
    public val random: WasiRandom = SystemWasiRandom,
    public val maxPathBytes: Int = 4096,
    public val maxIovecs: Int = 1024,
) {
    init {
        require(maxPathBytes > 0) { "maxPathBytes must be positive" }
        require(maxIovecs > 0) { "maxIovecs must be positive" }
        arguments.forEach { require('\u0000' !in it) { "arguments must not contain NUL" } }
        environment.forEach { (name, value) ->
            require(name.isNotEmpty()) { "environment names must not be empty" }
            require('=' !in name) { "environment names must not contain '='" }
            require('\u0000' !in name && '\u0000' !in value) {
                "environment entries must not contain NUL"
            }
        }
    }
}

/**
 * Non-trapping control transfer produced by `proc_exit`.
 *
 * Embedders normally catch this at their process boundary and use [exitCode]
 * as the guest program result.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasiProcessExit(
    public val exitCode: UInt,
) : Exception("WASI process exited with code $exitCode")

internal class WasiRandomException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal expect fun platformSecureRandomFill(destination: ByteArray)
