//
//  MusicSearch.kt
//  pandoracore
//
//  Port of PandoraCore/MusicSearch.swift — `music.search` results,
//  mirroring `pithos.pandora.Pandora.search()`:
//
//    artists: score ≥ 80 kept; songs: score ≥ 80 kept; genreStations: all.
//    Merged and sorted by score descending (Python's sort is stable, so
//    ties keep the artists → songs → genre merge order).
//
//  Every kind carries `musicToken` — pithos' `SearchResult.musicId` reads
//  it unconditionally, and its add-station dialog creates stations from
//  any kind via `add_station_by_music_id(musicId)`.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

data class MusicSearchResult(
    /** pithos `SearchResult.resultType`. */
    val kind: Kind,
    /**
     * Relevance score from the server (pithos filters `< 80` on
     * artist/song; genre passes through with whatever it sent).
     */
    val score: kotlin.Int,
    /** Seed token for `station.createStation` (`musicToken`). */
    val musicToken: String,
    /** Primary label: song title, artist name, or genre station name. */
    val title: String,
    /** Artist name for songs; null otherwise. */
    val subtitle: String? = null,
) {
    enum class Kind {
        artist,
        song,
        genre,
    }

    /**
     * Sections can theoretically repeat a token across kinds; kind+token
     * is unique in practice and stable across reloads.
     */
    val id: String
        get() = "${kind.name}:$musicToken"

    companion object {
        /**
         * Parses `music.search`'s unwrapped result object into pithos'
         * filter-and-sort order. A malformed entry is skipped (one bad seed
         * must not fail the whole sheet) — pithos would crash instead.
         */
        fun from(result: Map<String, AnyJSON>): List<MusicSearchResult> {
            data class Hit(val order: kotlin.Int, val hit: MusicSearchResult)

            val merged = mutableListOf<Hit>()
            var order = 0

            fun append(hit: MusicSearchResult?) {
                if (hit != null) {
                    merged.add(Hit(order, hit))
                    order++
                }
            }

            for (entry in result["artists"]?.arrayValue ?: emptyList()) {
                // pithos: `if i['score'] >= 80`
                val hit = entry.objectValue?.let { artistOrSong(it, Kind.artist) }
                if (hit == null || hit.score < 80) continue
                append(hit)
            }
            for (entry in result["songs"]?.arrayValue ?: emptyList()) {
                val hit = entry.objectValue?.let { artistOrSong(it, Kind.song) }
                if (hit == null || hit.score < 80) continue
                append(hit)
            }
            for (entry in result["genreStations"]?.arrayValue ?: emptyList()) {
                val obj = entry.objectValue ?: continue
                val name = obj["stationName"]?.stringValue ?: continue
                val token = obj["musicToken"]?.stringValue ?: continue
                val score = obj["score"]?.intValue ?: continue
                append(MusicSearchResult(kind = Kind.genre, score = score.toInt(), musicToken = token, title = name))
            }

            // Score descending; original merge order breaks ties (pithos relies
            // on Python's stable sort with the same key).
            return merged.sortedWith(
                compareByDescending<Hit> { it.hit.score }.thenBy { it.order }
            ).map { it.hit }
        }

        private fun artistOrSong(entry: Map<String, AnyJSON>, kind: Kind): MusicSearchResult? {
            val token = entry["musicToken"]?.stringValue ?: return null
            val score = entry["score"]?.intValue ?: return null
            when (kind) {
                Kind.artist -> {
                    val name = entry["artistName"]?.stringValue ?: return null
                    return MusicSearchResult(kind = kind, score = score.toInt(), musicToken = token, title = name)
                }
                Kind.song -> {
                    val title = entry["songName"]?.stringValue ?: return null
                    return MusicSearchResult(
                        kind = kind, score = score.toInt(), musicToken = token,
                        title = title, subtitle = entry["artistName"]?.stringValue,
                    )
                }
                Kind.genre -> return null // handled above; kept so `kind` stays exhaustive
            }
        }
    }
}
