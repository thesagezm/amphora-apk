//
//  Playlist.kt
//  pandoracore
//
//  Port of PandoraCore/Playlist.swift — `station.getPlaylist` decoding:
//  one playlist entry → one `Track` with its audio variants, mirroring
//  the two references:
//
//  - `pithos/pandora/pandora.py` `Station.get_playlist` / `Song.__init__`:
//    request params (`stationToken`, `includeTrackLength`,
//    `additionalAudioUrl: 'HTTP_32_AACPLUS,HTTP_128_MP3'`), the wire fields,
//    and the quality promotion rule — when the account's `highQuality` is
//    below 128 kbps the extra 128 MP3 URL becomes the new high quality.
//  - pydora `pandora/models/playlist.py` `AudioField`: a response with a
//    bare `audioUrl` and no `audioUrlMap` still yields a playable
//    high-quality (aacplus/64) stream.
//
//  Entries without `songName` are skipped (pithos filters them) — except
//  advertisement entries, which carry `title` instead (pydora's `AdItem`);
//  those are kept and flagged (`is_ad`) so playback can resolve them via
//  `ad.getAdMetadata` and register the impression (`ad.registerAd`).
//
//  No Android, no UI — pandoracore stays portable.
//
//  Kotlin note: `fetchedAt` is `java.time.Instant` (Swift `Date`).
//

package pandoracore

import java.time.Instant

// MARK: Audio

/** One playable stream for a track: encoding, bitrate and URL. */
data class AudioVariant(
    /** Wire key of the quality bucket (`highQuality` / `mediumQuality` /
     *  `lowQuality`) this variant came from. */
    val quality: Quality,
    val encoding: String,
    val bitrate: kotlin.Int,
    val url: String,
) {
    enum class Quality {
        highQuality,
        mediumQuality,
        lowQuality;

        /** Higher rank = preferred. */
        val rank: kotlin.Int
            get() = when (this) {
                highQuality -> 3
                mediumQuality -> 2
                lowQuality -> 1
            }
    }

    val id: String
        get() = "${quality.name}:$encoding:$bitrate"
}

// MARK: Track

/** One track from a station playlist. */
data class Track(
    val trackToken: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    /** Track length in seconds (`trackLength`, with `includeTrackLength`). */
    val duration: kotlin.Int,
    val stationId: String,
    /**
     * Pandora's `songRating`: 1 = thumbs up, -1 = thumbs down,
     * 0 = unrated (pithos `RATE_LOVE` / `RATE_BAN` / `RATE_NONE`).
     */
    val rating: Rating,
    /** Pandora's playback gain hint (`trackGain`) — informational only. */
    val gain: Double,
    /**
     * Ads carry an `adToken`; they are not music (pydora `is_ad`).
     * Playlist ad entries are usually stubs without audio — resolve them
     * with `SessionManager.getAdMetadata` before playing.
     */
    val isAdvertisement: Boolean,
    /**
     * The ad token of an advertisement entry, null for music tracks
     * (pydora `AdItem.ad_token`; keys the resolve/register calls).
     */
    val adToken: String?,
    /**
     * Available streams, best first (highest quality bucket, then
     * bitrate). Empty when the server sent no playable URL.
     */
    val variants: List<AudioVariant>,
) {
    enum class Rating(val raw: kotlin.Int) {
        banned(-1),
        none(0),
        loved(1);

        companion object {
            private val byRaw = entries.associateBy { it.raw }
            fun from(raw: kotlin.Int): Rating? = byRaw[raw]
        }
    }

    val id: String
        get() = trackToken

    /**
     * The stream to play: highest available quality (pithos' default
     * `audio_quality = 'highQuality'`, pydora's downward search).
     */
    val preferredVariant: AudioVariant?
        get() = variants.firstOrNull()

    /** Playable URL, or null when Pandora sent no audio (ads, expired items). */
    val streamURL: String?
        get() = preferredVariant?.url

    companion object {
        /**
         * Parses the `items` array of a `station.getPlaylist` result.
         * Music entries without `songName` are dropped (pithos' filter);
         * advertisements are kept (see `fromEntry`); a malformed entry is
         * skipped, never fatal.
         */
        fun from(result: Map<String, AnyJSON>): List<Track> =
            (result["items"]?.arrayValue ?: emptyList()).mapNotNull { entry ->
                entry.objectValue?.let { fromEntry(it) }
            }

        /**
         * One playlist entry → one track.
         *
         * Music entries carry `songName` + `trackToken` (pithos' filter).
         * Advertisement entries carry `adToken` and use `title`/`companyName`
         * instead (pydora `AdItem`) and usually have no audio — they are kept,
         * flagged, and get a synthetic id (`ad.<adToken>`) when the entry has
         * no `trackToken`, so playback can resolve them (`ad.getAdMetadata`)
         * and register the impression (`ad.registerAd`) instead of silently
         * dropping them (a silent drop consumes the impression while never
         * playing anything).
         */
        fun fromEntry(entry: Map<String, AnyJSON>): Track? {
            val adToken = entry["adToken"]?.stringValue
            val title = entry["songName"]?.stringValue ?: entry["title"]?.stringValue
            val trackToken = entry["trackToken"]?.stringValue
                ?: adToken?.let { "ad.$it" }
            title ?: return null
            trackToken ?: return null
            val artist = entry["artistName"]?.stringValue
                ?: entry["companyName"]?.stringValue ?: ""
            val album = entry["albumName"]?.stringValue ?: ""
            val artwork = entry["albumArtUrl"]?.stringValue ?: entry["imageUrl"]?.stringValue
            val duration = entry.intLike("trackLength") ?: 0
            val stationId = entry.stringLike("stationId") ?: ""
            val rating = Rating.from(entry.intLike("songRating") ?: 0) ?: Rating.none
            val gain = entry.doubleLike("trackGain") ?: 0.0

            return Track(
                trackToken = trackToken,
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artwork,
                duration = duration,
                stationId = stationId,
                rating = rating,
                gain = gain,
                isAdvertisement = adToken != null,
                adToken = adToken,
                variants = variants(entry),
            )
        }

        /**
         * Builds the ordered variant list for one playlist entry.
         *
         * Wire shape (pithos `Song.__init__`):
         *
         *     "audioUrlMap": { "highQuality": { "bitrate": "64",
         *                         "encoding": "aacplus", "audioUrl": "…" } }
         *     "additionalAudioUrl": ["…aacplus-32…", "…mp3-128…"]
         *
         * Rules, in order:
         * 1. Parse every quality bucket that carries a URL and bitrate.
         * 2. With two additional URLs, promote the 128 MP3 to `highQuality`
         *    when the server's high quality is below 128 kbps (pithos), else
         *    keep the 32 AAC+ as a `lowQuality` fallback (pithos).
         * 3. A bare `audioUrl` without a map is treated as a 64 kbps aacplus
         *    high-quality stream (pydora `AudioField`).
         */
        private fun variants(entry: Map<String, AnyJSON>): List<AudioVariant> {
            val byQuality = mutableMapOf<AudioVariant.Quality, AudioVariant>()

            entry["audioUrlMap"]?.objectValue?.let { map ->
                for (quality in AudioVariant.Quality.entries) {
                    val bucket = map[quality.name]?.objectValue ?: continue
                    val url = bucket["audioUrl"]?.stringValue ?: continue
                    if (url.isEmpty()) continue
                    val bitrate = bucket.intLike("bitrate") ?: continue
                    val encoding = bucket["encoding"]?.stringValue ?: "unknown"
                    byQuality[quality] = AudioVariant(
                        quality = quality, encoding = encoding, bitrate = bitrate, url = url,
                    )
                }
            }

            // Additional URLs: index 0 = HTTP_32_AACPLUS, index 1 = HTTP_128_MP3
            // (the order Amphora requests them in).
            val additional = (entry["additionalAudioUrl"]?.arrayValue ?: emptyList())
                .mapNotNull { it.stringValue }
            if (additional.size >= 2) {
                val high = byQuality[AudioVariant.Quality.highQuality]
                if (high != null && high.bitrate < 128) {
                    // The 128 MP3 stream is better than what we were offered:
                    // it becomes the new high quality, the old high drops to
                    // medium (pithos' exact promotion).
                    byQuality[AudioVariant.Quality.mediumQuality] = AudioVariant(
                        quality = AudioVariant.Quality.mediumQuality,
                        encoding = high.encoding,
                        bitrate = high.bitrate,
                        url = high.url,
                    )
                    byQuality[AudioVariant.Quality.highQuality] = AudioVariant(
                        quality = AudioVariant.Quality.highQuality,
                        encoding = "mp3", bitrate = 128, url = additional[1],
                    )
                } else if (high == null) {
                    byQuality[AudioVariant.Quality.highQuality] = AudioVariant(
                        quality = AudioVariant.Quality.highQuality,
                        encoding = "mp3", bitrate = 128, url = additional[1],
                    )
                    byQuality[AudioVariant.Quality.lowQuality] = AudioVariant(
                        quality = AudioVariant.Quality.lowQuality,
                        encoding = "aacplus", bitrate = 32, url = additional[0],
                    )
                } else {
                    byQuality[AudioVariant.Quality.lowQuality] = AudioVariant(
                        quality = AudioVariant.Quality.lowQuality,
                        encoding = "aacplus", bitrate = 32, url = additional[0],
                    )
                }
            }

            if (byQuality.isEmpty()) {
                entry["audioUrl"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { bare ->
                    // pydora: mobile tokens sometimes answer with a bare URL.
                    byQuality[AudioVariant.Quality.highQuality] = AudioVariant(
                        quality = AudioVariant.Quality.highQuality,
                        encoding = "aacplus", bitrate = 64, url = bare,
                    )
                }
            }

            return byQuality.values.sortedWith(
                compareByDescending<AudioVariant> { it.quality.rank }.thenByDescending { it.bitrate }
            )
        }
    }
}

// MARK: Playlist

/**
 * A fetched playlist plus the moment it was requested. Pandora's playlists
 * expire after an hour (pithos `PLAYLIST_VALIDITY_TIME`).
 */
data class Playlist(
    val tracks: List<Track>,
    val fetchedAt: Instant = Instant.now(),
) {
    /** How long a playlist stays usable (pithos `PLAYLIST_VALIDITY_TIME`). */
    fun isExpired(now: Instant = Instant.now()): Boolean =
        now.epochSecond - fetchedAt.epochSecond >= VALIDITY_TIME

    companion object {
        /** How long a playlist stays usable (pithos `PLAYLIST_VALIDITY_TIME`). */
        const val VALIDITY_TIME: Long = 60 * 60
    }
}

// MARK: Flexible field access

/**
 * Numbers arrive as JSON numbers or as numeric strings (pithos reads
 * `int(d['audioUrlMap']['highQuality']['bitrate'])` — a string).
 */
internal fun Map<String, AnyJSON>.intLike(key: String): kotlin.Int? {
    val value = this[key] ?: return null
    value.intValue?.let { return it.toInt() }
    value.stringValue?.let { return it.toIntOrNull() }
    return null
}

internal fun Map<String, AnyJSON>.doubleLike(key: String): Double? {
    val value = this[key] ?: return null
    value.doubleValue?.let { return it }
    value.stringValue?.let { return it.toDoubleOrNull() }
    return null
}

/** Ids arrive as numbers or strings depending on the endpoint. */
internal fun Map<String, AnyJSON>.stringLike(key: String): String? {
    val value = this[key] ?: return null
    value.stringValue?.let { return it }
    value.intValue?.let { return it.toString() }
    return null
}
