//
//  Serializers.kt
//  pandoracore
//
//  Port infrastructure (no Swift counterpart): Foundation's Codable
//  encodes `Date` automatically; kotlinx.serialization needs an explicit
//  serializer. Amphora's on-disk convention is epoch seconds as a Double
//  (matching TimeInterval semantics), applied wherever a Swift model was
//  Codable with a Date field.
//

package pandoracore

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** `Instant` ↔ epoch seconds (Double, TimeInterval semantics). */
object InstantEpochSecondsSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor(
        "pandoracore.InstantEpochSeconds",
        PrimitiveKind.DOUBLE,
    )

    override fun deserialize(decoder: Decoder): Instant =
        Instant.ofEpochSecond(decoder.decodeDouble().toLong())

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeDouble(value.epochSecond.toDouble())
    }
}
