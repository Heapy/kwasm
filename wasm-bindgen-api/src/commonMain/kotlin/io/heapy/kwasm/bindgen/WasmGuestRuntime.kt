package io.heapy.kwasm.bindgen

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Version 1 raw Kotlin/Wasm transport names and request envelope.
 *
 * Kotlin/Wasm owns and exports its linear memory as `memory`; the compiler
 * currently has no source annotation for assigning a second export name to
 * that memory.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasmGuestRuntimeAbi {
    public const val VERSION: Int = 1
    public const val REQUEST_HEADER_SIZE: Int = 20

    public const val MEMORY_EXPORT: String = "memory"
    public const val LEGACY_MEMORY_EXPORT: String = "__kwasm_bindgen_memory_v1"
    public const val ALLOCATE_EXPORT: String = "__kwasm_bindgen_alloc_v1"
    public const val FREE_EXPORT: String = "__kwasm_bindgen_free_v1"
    public const val INVOKE_EXPORT: String = "__kwasm_bindgen_invoke_v1"

    public const val HOST_IMPORT_MODULE: String = "kwasm:bindgen/v1"
    public const val HOST_BEGIN_IMPORT: String = "begin"
    public const val HOST_FINISH_IMPORT: String = "finish"
}

/**
 * Bounds applied to the raw routing envelope around a [WasmAbiCodec] message.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmGuestRuntimeLimits(
    public val maxBoundaryNameBytes: Int = 64 * 1024,
    public val maxFunctionNameBytes: Int = 64 * 1024,
    public val maxArgumentsBytes: Int = 16 * 1024 * 1024,
    public val maxResultBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(maxBoundaryNameBytes >= 0) { "maxBoundaryNameBytes must be non-negative" }
        require(maxFunctionNameBytes >= 0) { "maxFunctionNameBytes must be non-negative" }
        require(maxArgumentsBytes >= 0) { "maxArgumentsBytes must be non-negative" }
        require(maxResultBytes >= 0) { "maxResultBytes must be non-negative" }
    }
}

/** A decoded call-routing envelope. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmGuestRuntimeRequest(
    public val boundary: String,
    public val function: String,
    public val arguments: ByteArray,
)

/** Deterministic encoder/decoder for the version 1 `KWRQ` request envelope. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasmGuestRuntimeRequestCodec {
    public fun encode(
        boundary: String,
        function: String,
        arguments: ByteArray,
        limits: WasmGuestRuntimeLimits = WasmGuestRuntimeLimits(),
    ): ByteArray {
        val boundaryBytes = encodeWireName(boundary, "boundary")
        val functionBytes = encodeWireName(function, "function")
        requireSize("boundary", boundaryBytes.size, limits.maxBoundaryNameBytes)
        requireSize("function", functionBytes.size, limits.maxFunctionNameBytes)
        requireSize("arguments", arguments.size, limits.maxArgumentsBytes)

        val totalSize =
            WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE.toLong() +
                boundaryBytes.size +
                functionBytes.size +
                arguments.size
        require(totalSize <= Int.MAX_VALUE) {
            "kwasm bindgen request exceeds the wasm32 address space"
        }

        return ByteArray(totalSize.toInt()).also { request ->
            request[0] = 0x4B
            request[1] = 0x57
            request[2] = 0x52
            request[3] = 0x51
            request[4] = WasmGuestRuntimeAbi.VERSION.toByte()
            request.writeIntLittleEndian(8, boundaryBytes.size)
            request.writeIntLittleEndian(12, functionBytes.size)
            request.writeIntLittleEndian(16, arguments.size)

            var offset = WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE
            boundaryBytes.copyInto(request, destinationOffset = offset)
            offset += boundaryBytes.size
            functionBytes.copyInto(request, destinationOffset = offset)
            offset += functionBytes.size
            arguments.copyInto(request, destinationOffset = offset)
        }
    }

    public fun decode(
        request: ByteArray,
        limits: WasmGuestRuntimeLimits = WasmGuestRuntimeLimits(),
    ): WasmGuestRuntimeRequest {
        if (request.size < WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE) {
            throw WasmGuestRuntimeProtocolException(
                "request is ${request.size} bytes; expected at least " +
                    WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE,
            )
        }
        if (
            request[0] != 0x4B.toByte() ||
            request[1] != 0x57.toByte() ||
            request[2] != 0x52.toByte() ||
            request[3] != 0x51.toByte()
        ) {
            throw WasmGuestRuntimeProtocolException("request magic is not KWRQ")
        }
        val version = request[4].toInt() and 0xFF
        if (version != WasmGuestRuntimeAbi.VERSION) {
            throw WasmGuestRuntimeProtocolException(
                "unsupported request version $version; expected ${WasmGuestRuntimeAbi.VERSION}",
            )
        }
        if (
            request[5].toInt() != 0 ||
            request[6].toInt() != 0 ||
            request[7].toInt() != 0
        ) {
            throw WasmGuestRuntimeProtocolException("request flags and reserved bytes must be zero")
        }

        val boundarySize = request.readLength(8, "boundary")
        val functionSize = request.readLength(12, "function")
        val argumentsSize = request.readLength(16, "arguments")
        requireDecodedSize("boundary", boundarySize, limits.maxBoundaryNameBytes)
        requireDecodedSize("function", functionSize, limits.maxFunctionNameBytes)
        requireDecodedSize("arguments", argumentsSize, limits.maxArgumentsBytes)

        val payloadSize =
            boundarySize.toLong() + functionSize.toLong() + argumentsSize.toLong()
        val expectedSize = WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE.toLong() + payloadSize
        if (expectedSize != request.size.toLong()) {
            throw WasmGuestRuntimeProtocolException(
                "request fields require $expectedSize bytes, received ${request.size}",
            )
        }

        var offset = WasmGuestRuntimeAbi.REQUEST_HEADER_SIZE
        val boundary = request.decodeWireName(offset, boundarySize, "boundary")
        offset += boundarySize
        val function = request.decodeWireName(offset, functionSize, "function")
        offset += functionSize
        return WasmGuestRuntimeRequest(
            boundary = boundary,
            function = function,
            arguments = request.copyOfRange(offset, offset + argumentsSize),
        )
    }

    private fun encodeWireName(value: String, field: String): ByteArray {
        val bytes = value.encodeToByteArray()
        if (bytes.decodeToString(throwOnInvalidSequence = true) != value) {
            throw IllegalArgumentException("$field wire name is not valid Unicode")
        }
        return bytes
    }

    private fun requireSize(field: String, actual: Int, maximum: Int) {
        require(actual <= maximum) {
            "$field byte size $actual exceeds limit $maximum"
        }
    }

    private fun requireDecodedSize(field: String, actual: Int, maximum: Int) {
        if (actual > maximum) {
            throw WasmGuestRuntimeProtocolException(
                "$field byte size $actual exceeds limit $maximum",
            )
        }
    }
}

/** A malformed or unsupported raw guest transport request. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmGuestRuntimeProtocolException(
    detail: String,
) : IllegalArgumentException("Malformed kwasm guest request: $detail")

/** No generated guest implementation has been installed for a route. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmGuestBindingNotInstalledException(
    public val boundary: String,
    public val function: String,
) : IllegalStateException("No kwasm guest binding installed for '$boundary'/'$function'")

/**
 * A suspend IDL implementation yielded instead of completing in the current
 * Kotlin/Wasm call stack.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmGuestSuspensionNotSupportedException(
    public val boundary: String,
    public val function: String,
) : IllegalStateException(
    "Kotlin/Wasm guest function '$boundary'/'$function' suspended; " +
        "bindgen version 1 only supports synchronous completion in guest code",
)

/**
 * Process-local routing table used by generated Kotlin/Wasm exports.
 *
 * A generated `FooGuestExports(implementation).install()` call registers all
 * members atomically. Duplicate routes are rejected rather than silently
 * replacing a live implementation.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasmGuestExportRegistry {
    private val bindings: MutableMap<Route, WasmExportBinding> = mutableMapOf()

    public fun install(newBindings: List<WasmExportBinding>) {
        val routes = newBindings.map { Route(it.boundary, it.function) }
        val duplicate = routes.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value > 1 }
            ?.key
        require(duplicate == null) {
            "duplicate kwasm guest route '${duplicate?.boundary}'/'${duplicate?.function}'"
        }
        val occupied = routes.firstOrNull { it in bindings }
        require(occupied == null) {
            "kwasm guest route '${occupied?.boundary}'/'${occupied?.function}' is already installed"
        }
        routes.zip(newBindings).forEach { (route, binding) ->
            bindings[route] = binding
        }
    }

    /**
     * Dispatches a guest call and requires a suspend implementation to finish
     * before its initial invocation returns.
     */
    public fun dispatchBlocking(
        boundary: String,
        function: String,
        arguments: ByteArray,
    ): ByteArray {
        val binding = bindings[Route(boundary, function)]
            ?: throw WasmGuestBindingNotInstalledException(boundary, function)
        var completion: Result<ByteArray>? = null
        suspend { binding.invoke(arguments) }.startCoroutine(
            object : Continuation<ByteArray> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<ByteArray>) {
                    completion = result
                }
            },
        )
        return completion?.getOrThrow()
            ?: throw WasmGuestSuspensionNotSupportedException(boundary, function)
    }

    private data class Route(
        val boundary: String,
        val function: String,
    )
}

private fun ByteArray.writeIntLittleEndian(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

private fun ByteArray.readLength(offset: Int, field: String): Int {
    val value =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    if (value < 0) {
        throw WasmGuestRuntimeProtocolException("$field length exceeds Int.MAX_VALUE")
    }
    return value
}

private fun ByteArray.decodeWireName(offset: Int, size: Int, field: String): String =
    try {
        decodeToString(
            startIndex = offset,
            endIndex = offset + size,
            throwOnInvalidSequence = true,
        )
    } catch (_: Exception) {
        throw WasmGuestRuntimeProtocolException("$field wire name is not strict UTF-8")
    }
