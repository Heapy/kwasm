package io.heapy.kwasm.bindgen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor

/**
 * Deterministic binary payload codec used for `@Serializable` boundary types.
 *
 * The enclosing [WasmAbiCodec] supplies framing, limits, and the kwasm ABI
 * version. The schema is supplied statically by generated code, so this path
 * uses no reflection and works on every Kotlin Multiplatform target.
 */
@OptIn(ExperimentalSerializationApi::class)
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasmCompositeCodec {
    private val format: Cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    public fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        format.encodeToByteArray(serializer, value)

    public fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        format.decodeFromByteArray(serializer, bytes)
}
