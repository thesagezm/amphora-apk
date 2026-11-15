//
//  PandoraAPI.kt
//  pandoracore
//
//  Port of PandoraCore/PandoraAPI.swift — legacy Pandora JSON API (v5)
//  surface constants.
//
//  Every value here is taken from the reference implementation
//  (`pithos/pandora/data.py`, `android-generic` client entry) rather than
//  guessed. Methods that vary per request are declared on `PandoraAPIClient`.
//

package pandoracore

object PandoraAPI {

    /** Hostname of the legacy JSON-RPC endpoint. Pandora has no separate TLS
     *  path: every call is a TLS-encrypted POST to this host. */
    const val baseURL: String = "https://tuner.pandora.com/services/json/"

    /** The `method` values this client issues. */
    object Method {
        const val partnerLogin = "auth.partnerLogin"
        const val userLogin = "auth.userLogin"
        const val getStationList = "user.getStationList"
        const val createStation = "station.createStation"
        const val renameStation = "station.renameStation"
        const val deleteStation = "station.deleteStation"
        const val transformSharedStation = "station.transformSharedStation"
        const val musicSearch = "music.search"
        const val getGenreStations = "station.getGenreStations"
        const val getPlaylist = "station.getPlaylist"
        const val addFeedback = "station.addFeedback"
        const val deleteFeedback = "station.deleteFeedback"
        const val getBookmarks = "user.getBookmarks"
        const val getUserSettings = "user.getSettings"
        const val addSongBookmark = "bookmark.addSongBookmark"
        const val addArtistBookmark = "bookmark.addArtistBookmark"
        const val deleteSongBookmark = "bookmark.deleteSongBookmark"
        const val deleteArtistBookmark = "bookmark.deleteArtistBookmark"
        const val sleepSong = "user.sleepSong"
        const val getAdMetadata = "ad.getAdMetadata"
        const val registerAd = "ad.registerAd"
    }

    /** Extra stream formats requested alongside a playlist, in the order
     *  pithos asks for them: `additionalAudioUrl[0]` = 32 kbps AAC+,
     *  `additionalAudioUrl[1]` = 128 kbps MP3. */
    const val additionalAudioURLs = "HTTP_32_AACPLUS,HTTP_128_MP3"

    /** Client-side identity used by `auth.partnerLogin`, matching the
     *  `android-generic` entry in `pithos/pandora/data.py`. */
    const val partnerUsername = "android"
    const val partnerPassword = "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7"
    const val partnerDeviceModel = "android-generic"
    const val version = "5"

    /** Blowfish key used to *encode* request bodies (password encryption,
     *  `station.getPlaylist` payload). Source: `encryptKey`,
     *  `pithos/pandora/data.py`. */
    const val encryptKey = "6#26FRL\$ZWD"

    /** Blowfish key used to *decode* server responses (`syncTime`).
     *  Source: `decryptKey`, `pithos/pandora/data.py`. */
    const val decryptKey = "R=U!LH\$O2B#"
}
