//
//  Advertisement.kt
//  pandoracore
//
//  Port of PandoraCore/Advertisement.swift — audio advertisements on free
//  accounts, after pydora `pandora/models/ad.py` (pithos has no ad
//  handling at all):
//
//  - A playlist ad entry is a stub — `adToken` + display fields, usually
//    no audio. It is resolved with `ad.getAdMetadata` (params
//    `returnAdTrackingTokens=true`, `supportAudioAds=true`).
//  - The impression is registered with `ad.registerAd`, carrying the
//    station id and the tracking tokens from the metadata reply — pydora
//    `AdItem.prepare_playback` / `register_ad`.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

sealed class AdvertisementError : Exception() {
    /**
     * `registerAd` refuses empty tracking-token lists before any network
     * (pydora `ParameterMissing`: "No ad tracking tokens provided").
     */
    data object MissingTrackingTokens : AdvertisementError()

    /** The track passed to `prepareAd` is not an advertisement. */
    data object NotAnAdvertisement : AdvertisementError()
}

/** One resolved audio advertisement (pydora `AdItem` fields). */
data class Advertisement(
    val adToken: String,
    val title: String = "",
    val companyName: String = "",
    /**
     * Tracking tokens from the metadata reply
     * (`returnAdTrackingTokens=true`); empty when the reply omitted them.
     */
    val trackingTokens: List<String> = emptyList(),
    val audioURL: String? = null,
    val imageURL: String? = null,
    val clickThroughURL: String? = null,
) {
    companion object {
        /**
         * Decodes the `ad.getAdMetadata` result object — pydora builds its
         * `AdItem` straight from that dict. Missing display fields are
         * tolerated (pydora fields default to None); the adToken is the one
         * the request was made with.
         */
        fun from(adToken: String, result: Map<String, AnyJSON>): Advertisement = Advertisement(
            adToken = adToken,
            title = result["title"]?.stringValue ?: "",
            companyName = result["companyName"]?.stringValue ?: "",
            trackingTokens = (result["adTrackingTokens"]?.arrayValue ?: emptyList())
                .mapNotNull { it.stringValue },
            audioURL = result["audioUrl"]?.stringValue,
            imageURL = result["imageUrl"]?.stringValue,
            clickThroughURL = result["clickThroughUrl"]?.stringValue,
        )
    }

    /**
     * The ad as a player-queue slot. pydora's `AudioField` treats a bare
     * `audioUrl` as a 64 kbps aacplus high-quality stream — same shape
     * here. `streamURL` is null when the metadata carried no audio, in
     * which case the player steps over the ad.
     */
    fun asTrack(stationId: String): Track = Track(
        trackToken = "ad.$adToken",
        title = title,
        artist = companyName,
        album = "",
        artworkUrl = imageURL,
        duration = 0,
        stationId = stationId,
        rating = Track.Rating.none,
        gain = 0.0,
        isAdvertisement = true,
        adToken = adToken,
        variants = audioURL?.let {
            listOf(AudioVariant(quality = AudioVariant.Quality.highQuality, encoding = "aacplus", bitrate = 64, url = it))
        } ?: emptyList(),
    )
}
