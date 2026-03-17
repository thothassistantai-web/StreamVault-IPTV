package com.streamvault.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int = decodePrimitive(decoder)?.toIntOrNull() ?: 0

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? = decodePrimitive(decoder)?.toIntOrNull()

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeInt(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long = decodePrimitive(decoder)?.toLongOrNull() ?: 0L

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? = decodePrimitive(decoder)?.toLongOrNull()

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeLong(value)
        }
    }
}

private fun decodePrimitive(decoder: Decoder): String? {
    return when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().primitiveContentOrNull()
        else -> runCatching { decoder.decodeString() }.getOrNull()
    }
}

private fun JsonElement.primitiveContentOrNull(): String? {
    if (this == JsonNull) return null
    val primitive = this as? JsonPrimitive ?: throw SerializationException("Expected JSON primitive")
    return if (primitive.isString) primitive.content else primitive.toString()
}