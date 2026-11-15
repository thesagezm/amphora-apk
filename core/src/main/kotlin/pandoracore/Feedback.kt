//
//  Feedback.kt
//  pandoracore
//
//  Port of PandoraCore/Feedback.swift — thumbs up / down and the "why"
//  state, mirroring the references:
//
//  - `pithos/pandora/pandora.py` `Pandora.add_feedback` /
//    `delete_feedback` / `Song.rate`: `station.addFeedback` with
//    `trackToken` + `isPositive`, the reply carries `feedbackId`;
//    `station.deleteFeedback` with `feedbackId` (+ `stationToken`).
//  - libpiano `source/request.c` (`PIANO_REQUEST_ADD_FEEDBACK`,
//    `PIANO_REQUEST_DELETE_FEEDBACK`): adds `stationToken` to the add
//    call; the delete call carries `feedbackId` only.
//  - pydora `pandora/client.py`: `add_feedback(track_token, positive)`
//    and `delete_feedback(feedback_id)`.
//
//  The legacy v5 API has **no reason/"why" parameter** in any of those
//  references — a dislike is a plain `isPositive: false`. Amphora
//  therefore does not invent a reason field; the reason list stays out
//  of scope until a reference documents it.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import java.time.Instant

// MARK: Rating

/** The three ratings Pandora's legacy API understands. */
enum class FeedbackRating {
    /** Thumbs up (`isPositive: true`). */
    loved,
    /** Thumbs down (`isPositive: false`). */
    banned,
    /** No rating — the feedback is removed. */
    none;

    /**
     * The `isPositive` flag the API expects. `none` never reaches the
     * API (a rating is deleted instead).
     */
    val isPositive: Boolean
        get() = this == loved
}

// MARK: Feedback

/** The result of a rating call: the id needed to undo the rating later. */
data class FeedbackReceipt(
    /** `feedbackId` from the `station.addFeedback` reply (pithos'
     *  `Pandora.add_feedback` returns exactly this). */
    val feedbackId: String,
) {
    companion object {
        /** Decodes the reply of `station.addFeedback`. */
        fun from(result: Map<String, AnyJSON>): FeedbackReceipt {
            val id = result["feedbackId"]?.stringValue
                ?: throw PandoraMalformedResponseError("addFeedback reply missing feedbackId")
            return FeedbackReceipt(id)
        }
    }
}

// MARK: Track rating result

/**
 * The outcome of a rating change: the track's rating afterwards, and
 * the `feedbackId` that rating now carries (null when the rating was
 * removed — there is no feedback left to undo).
 *
 * The id matters because a rating can only be *replaced* or *removed*
 * by its id, and the id is not known for a song rated before this
 * device saw it.
 */
data class TrackRatingResult(
    /** The rating the track carries now. */
    val rating: FeedbackRating,
    /** The feedback id of the new rating, null when unrated. */
    val feedbackId: String?,
)

// MARK: Bookmarks

/**
 * One bookmarked artist or song (`user.getBookmarks`).
 *
 * Wire fields follow pydora's `pandora/models/bookmark.py`:
 * `musicToken`, `artistName`, `artUrl`, `bookmarkToken`, `dateCreated`,
 * plus `songName` / `albumName` / `sampleUrl` / `sampleGain` for songs.
 */
data class Bookmark(
    /** Whether this bookmark is a song or the whole artist. */
    val kind: Kind,
    /** `bookmarkToken` — what the delete calls need. */
    val bookmarkToken: String,
    /** `musicToken` — the seed behind the bookmark. */
    val musicToken: String,
    val artistName: String,
    /** Song title (null for artist bookmarks). */
    val songName: String?,
    /** Album title (null for artist bookmarks). */
    val albumName: String?,
    val artworkUrl: String?,
    /** `dateCreated` — epoch seconds when the server sent a number. */
    val createdAt: Instant?,
) {
    enum class Kind {
        song,
        artist,
    }

    val id: String
        get() = bookmarkToken

    /**
     * Display title: the song title for songs, the artist name for
     * artist bookmarks.
     */
    val title: String
        get() = if (!songName.isNullOrEmpty()) songName else artistName

    companion object {
        /**
         * Parses `user.getBookmarks`'s result: `songs` and `artists`
         * (pydora's `BookmarkList`).
         */
        fun from(result: Map<String, AnyJSON>): List<Bookmark> {
            val list = mutableListOf<Bookmark>()
            for (entry in result["songs"]?.arrayValue ?: emptyList()) {
                entry.objectValue?.let { obj ->
                    bookmark(obj, Kind.song)?.let { list.add(it) }
                }
            }
            for (entry in result["artists"]?.arrayValue ?: emptyList()) {
                entry.objectValue?.let { obj ->
                    bookmark(obj, Kind.artist)?.let { list.add(it) }
                }
            }
            return list
        }

        private fun bookmark(entry: Map<String, AnyJSON>, kind: Kind): Bookmark? {
            val token = entry["bookmarkToken"]?.stringValue ?: return null
            val musicToken = entry["musicToken"]?.stringValue ?: return null
            val artist = entry["artistName"]?.stringValue ?: ""
            val song = entry["songName"]?.stringValue
            val album = entry["albumName"]?.stringValue
            val artwork = entry["artUrl"]?.stringValue
            var created: Instant? = null
            entry["dateCreated"]?.doubleValue?.let { seconds ->
                created = Instant.ofEpochSecond(seconds.toLong())
            } ?: entry["dateCreated"]?.stringValue?.toDoubleOrNull()?.let { seconds ->
                created = Instant.ofEpochSecond(seconds.toLong())
            }
            return Bookmark(
                kind = kind,
                bookmarkToken = token,
                musicToken = musicToken,
                artistName = artist,
                songName = song,
                albumName = album,
                artworkUrl = artwork,
                createdAt = created,
            )
        }
    }
}
