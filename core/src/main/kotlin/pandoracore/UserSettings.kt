//
//  UserSettings.kt
//  pandoracore
//
//  Port of PandoraCore/UserSettings.swift — the account-level settings
//  Pandora exposes (`user.getSettings`).
//
//  The field Amphora uses is the **explicit-content filter**. It is an
//  account-level switch, not a per-track flag: pithos reads it from
//  `Pandora.explicit_content_filter_state` — `user.getSettings` over
//  HTTPS with an encrypted body (the same shape as every other
//  authenticated call) — and the reply carries
//  `isExplicitContentFilterEnabled` plus
//  `isExplicitContentFilterPINProtected`.
//
//  Why this matters for the UI: there is **no per-item explicit flag on
//  the wire**, so a per-station badge would be invented data. The
//  honest surface is one account-level badge: when the filter is on,
//  explicit tracks are hidden for this account.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

/** The account's user settings (`user.getSettings`). */
data class UserSettings(
    /** `true` when Pandora hides explicit tracks for this account. */
    val isExplicitContentFilterEnabled: Boolean,
    /**
     * `true` when the filter is locked behind a PIN. The app only
     * reports it — changing the filter is out of scope.
     */
    val isExplicitContentFilterPINProtected: Boolean = false,
) {
    companion object {
        /**
         * Decodes the `user.getSettings` result.
         *
         * A missing key means "off" — pithos reads the field with
         * `.get('isExplicitContentFilterEnabled', False)`, so an older
         * server that does not send the key is treated the same way.
         */
        fun from(result: Map<String, AnyJSON>): UserSettings = UserSettings(
            isExplicitContentFilterEnabled =
                result["isExplicitContentFilterEnabled"]?.boolValue ?: false,
            isExplicitContentFilterPINProtected =
                result["isExplicitContentFilterPINProtected"]?.boolValue ?: false,
        )
    }
}
