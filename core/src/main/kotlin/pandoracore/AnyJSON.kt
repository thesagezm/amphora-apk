//
//  AnyJSON.kt
//  pandoracore
//
//  Port of PandoraCore/AnyJSON.swift — a minimal dynamic JSON value: typed
//  access without `Any` or key-path boilerplate, enough for decoding API
//  results field-by-field. The Swift `Codable` conformance becomes a
//  kotlinx.serialization serializer over JsonElement.
//

package pandoracore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class AnyJSON {
    data class Str(val value: String) : AnyJSON()
    data class Number(val value: Double) : AnyJSON()
    data class Bool(val value: Boolean) : AnyJSON()
    data class Object(val value: Map<String, AnyJSON>) : AnyJSON()
    data class Array(val value: List<AnyJSON>) : AnyJSON()
    data object Null : AnyJSON()

    // MARK: Typed access

    val stringValue: String?
        get() = (this as? Str)?.value

    val intValue: Long?
        get() = (this as? Number)?.value?.let { if (it.isFinite() && it <= Long.MAX_VALUE.toDouble()) it.toLong() else null }

    val doubleValue: Double?
        get() = (this as? Number)?.value

    val boolValue: Boolean?
        get() = (this as? Bool)?.value

    val objectValue: Map<String, AnyJSON>?
        get() = (this as? Object)?.value

    val arrayValue: List<AnyJSON>?
        get() = (this as? Array)?.value

    operator fun get(key: String): AnyJSON? = objectValue?.get(key)

    // MARK: JsonElement bridge

    fun toJsonElement(): JsonElement = when (this) {
        is Str -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Bool -> JsonPrimitive(value)
        is Object -> buildJsonObject {
            value.forEach { (k, v) -> put(k, v.toJsonElement()) }
        }
        is Array -> buildJsonArray { value.forEach { add(it.toJsonElement()) } }
        Null -> JsonNull
    }

    companion object {
        /** Decoding order matches the Swift init: nil → Bool → String → Double → object → array. */
        fun fromJsonElement(element: JsonElement): AnyJSON = when (element) {
            is JsonNull -> Null
            is JsonPrimitive -> {
                if (!element.isString) {
                    element.booleanOrNull?.let { return Bool(it) }
                    element.doubleOrNull?.let { return Number(it) }
                    // A bare number token that is not a valid double (e.g.
                    // an integer beyond double range) still decodes as a
                    // number via its text, or fails like Swift does.
                    return Number(element.content.toDouble())
                }
                Str(element.content)
            }
            is JsonObject -> Object(element.mapValues { (_, v) -> fromJsonElement(v) })
            is JsonArray -> Array(element.map { fromJsonElement(it) })
        }

        fun serializer(): KSerializer<AnyJSON> = AnyJSONSerializer
    }
}

internal object AnyJSONSerializer : KSerializer<AnyJSON> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): AnyJSON {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("AnyJSON requires JSON format")
        return AnyJSON.fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: AnyJSON) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("AnyJSON requires JSON format")
        jsonEncoder.encodeJsonElement(value.toJsonElement())
    }
}
