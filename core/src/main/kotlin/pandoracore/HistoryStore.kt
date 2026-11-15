//
//  HistoryStore.kt
//  pandoracore
//
//  Port of PandoraCore/HistoryStore.swift — local playback history —
//  "recently played" (AGENTS.md feature set). Pure logic: a bounded list
//  of played tracks, newest first, with de-duplication by track token.
//  No UI, no Android — the app target persists it to disk
//  (serialization-friendly value type).
//
//  Kotlin note: the Swift struct's `mutating` methods become in-place
//  mutations of a data class's private field (same call sites).
//

package pandoracore

import java.time.Instant
import kotlinx.serialization.Serializable

/** A bounded, newest-first history of played tracks.
 *
 *  The cap keeps memory and disk usage flat: the oldest entry falls off
 *  when the list grows past `capacity`. */
@Serializable
data class PlayHistory(
    /** Newest first. */
    private var entries: List<Entry> = emptyList(),
    /** The maximum number of entries kept. */
    val capacity: kotlin.Int = DEFAULT_CAPACITY,
) {
    init {
        trim()
    }

    /** One remembered play. */
    @Serializable
    data class Entry(
        val trackToken: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String? = null,
        /**
         * The station the track was played from (`stationToken`) — the
         * rating of a past track belongs to that station, so the
         * Recents list can rate it later. Null for entries recorded
         * before this field existed.
         */
        val stationToken: String? = null,
        /**
         * The rating the track carried when it played (`songRating`),
         * and the rating the user gives it later from the Recents
         * list. Null = unknown/unrated.
         */
        var rating: FeedbackRating? = null,
        /** When the track was played (epoch seconds). */
        @kotlinx.serialization.Serializable(with = InstantEpochSecondsSerializer::class)
        val playedAt: Instant,
    ) {
        val id: String
            get() = trackToken

        companion object {
            /** Builds an entry from a played track. */
            fun from(track: Track, stationToken: String? = null, playedAt: Instant = Instant.now()): Entry =
                Entry(
                    trackToken = track.trackToken,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = track.artworkUrl,
                    stationToken = stationToken,
                    rating = null,
                    playedAt = playedAt,
                )
        }
    }

    companion object {
        /** How many plays are remembered. */
        const val DEFAULT_CAPACITY = 100
    }

    /** Whether anything has been played yet. */
    val isEmpty: Boolean
        get() = entries.isEmpty()

    /** The remembered plays, newest first. */
    val count: kotlin.Int
        get() = entries.size

    /** The plays as a plain list (for `LazyColumn`/`items`). */
    val items: List<Entry>
        get() = entries

    /**
     * Records a play. A track that is already in the history moves to
     * the front instead of appearing twice.
     *
     * A re-listen keeps the rating the user already gave the track —
     * the rating belongs to the song, not to the play.
     */
    fun record(track: Track, stationToken: String? = null, at: Instant = Instant.now()) {
        val existingRating = entries.firstOrNull { it.trackToken == track.trackToken }?.rating
        val entry = Entry.from(track = track, stationToken = stationToken, playedAt = at)
        val kept = entries.filter { it.trackToken != entry.trackToken }
        val stored = if (existingRating != null) entry.copy(rating = existingRating) else entry
        entries = listOf(stored) + kept
        trim()
    }

    /** Sets the rating of a remembered track. Returns false when the
     *  track is not in the history. */
    fun setRating(rating: FeedbackRating?, trackToken: String): Boolean {
        val index = entries.indexOfFirst { it.trackToken == trackToken }
        if (index < 0) return false
        entries[index].rating = rating
        return true
    }

    /** The rating of a remembered track, if it has one. */
    fun rating(trackToken: String): FeedbackRating? =
        entries.firstOrNull { it.trackToken == trackToken }?.rating

    /** Drops the oldest entries until the list fits the capacity. */
    private fun trim() {
        if (entries.size > capacity) {
            entries = entries.take(capacity)
        }
    }
}
