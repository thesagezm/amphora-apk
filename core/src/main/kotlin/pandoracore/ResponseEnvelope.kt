//
//  ResponseEnvelope.kt
//  pandoracore
//
//  Port of PandoraCore/ResponseEnvelope.swift — decodes Pandora's JSON-RPC
//  envelope, mirroring `pithos.pandora.json_call` response handling:
//
//      {"stat": "ok",   "result": {...}}
//      {"stat": "fail", "code": 1001, "message": "..."}
//
//  `stat == "fail"` → typed `PandoraAPIError` (never crash).
//  `stat == "ok"`   → the `result` value.
//
//  No logging of tokens or full bodies: only the fault code and message.
//

package pandoracore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ResponseEnvelope {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decodes `payload` and returns the `result` object, or throws a typed
     * error for a fault or a malformed body.
     */
    fun result(payload: ByteArray): Map<String, AnyJSON> {
        val envelope = try {
            val text = payload.decodeToString()
            val root = Json.parseToJsonElement(text).jsonObject
            Envelope(
                stat = root["stat"]?.jsonPrimitive?.content
                    ?: throw PandoraMalformedResponseError("undecodable response body"),
                code = (root["code"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { !it.isString }?.content?.toLongOrNull(),
                message = (root["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                result = (root["result"] as? JsonObject)
                    ?.mapValues { (_, v) -> AnyJSON.fromJsonElement(v) },
            )
        } catch (e: PandoraMalformedResponseError) {
            throw e
        } catch (e: Exception) {
            throw PandoraMalformedResponseError("undecodable response body")
        }
        return when (envelope.stat) {
            "ok" -> envelope.result
                ?: throw PandoraMalformedResponseError("ok response missing result")
            "fail" -> throw PandoraAPIError(
                code = envelope.code?.toInt()
                    ?: throw PandoraMalformedResponseError("fail response missing code"),
                message = envelope.message ?: "unknown error",
            )
            else -> throw PandoraMalformedResponseError("unknown stat ${envelope.stat}")
        }
    }

    private data class Envelope(
        val stat: String,
        val code: Long?,
        val message: String?,
        val result: Map<String, AnyJSON>?,
    )
}

//
// Typed field helpers (fixture-decodable, no Any casts at call sites).
// Swift locates these extensions on LegacyPandoraClient.swift; the Kotlin
// port keeps them beside the envelope they read from. Same behavior.
//

fun Map<String, AnyJSON>.string(key: String): String =
    this[key]?.stringValue
        ?: throw PandoraMalformedResponseError("missing string field $key")

/** Swift `Int` is 64-bit; the Kotlin equivalent returns Long. */
fun Map<String, AnyJSON>.int(key: String): Long =
    this[key]?.intValue
        ?: throw PandoraMalformedResponseError("missing int field $key")

fun Map<String, AnyJSON>.bool(key: String): Boolean =
    this[key]?.boolValue
        ?: throw PandoraMalformedResponseError("missing bool field $key")
