//
//  CookieCsrfProvider.kt
//  app
//
//  The app-side port of PandoraCore's `CookieCSRFProvider` — the Swift
//  version read `HTTPCookieStorage.shared`; on Android the cookie jar
//  lives in OkHttp, so this adapter lives beside it. Same behavior:
//  request `https://www.pandora.com/` — HEAD, then GET — and read the
//  `csrftoken` cookie the response set. `refresh()` drops the cache and
//  bootstraps again (pandoCast `EnsureCsrfTokenAsync`).
//

package dev.personal.amphora.pandora

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import pandoracore.CSRFTokenProviding
import pandoracore.NetworkDefaults
import pandoracore.PandoraTransportError

class CookieCsrfProvider(
    private val webRootURL: String = "https://www.pandora.com/",
) : CSRFTokenProviding {

    private val lock = Any()
    private var cached: String? = null

    override suspend fun token(): String {
        read()?.let { return it }
        // A cookie from an earlier run may still be valid — try the jar
        // before the network.
        existingCookie()?.let { value ->
            store(value)
            return value
        }
        return bootstrap()
    }

    override suspend fun refresh(): String {
        clear()
        return bootstrap()
    }

    private suspend fun bootstrap(): String {
        for (method in listOf("HEAD", "GET")) {
            try {
                withContext(Dispatchers.IO) {
                    val call = AmphoraHttp.client.newCall(
                        Request.Builder()
                            .url(webRootURL)
                            .method(method, null)
                            .header("User-Agent", "Amphora")
                            .build(),
                    )
                    call.timeout().timeout(
                        (NetworkDefaults.requestTimeoutInterval * 1000).toLong(),
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                    call.execute().use { response ->
                        if (response.code == 200) {
                            existingCookie()?.let { value ->
                                store(value)
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // Swift used `try?` — a failed bootstrap attempt moves on.
            }
            read()?.let { return it }
        }
        throw PandoraTransportError("Pandora did not provide a csrftoken cookie")
    }

    /**
     * The `csrftoken` cookie for the web root — server sets it on
     * `.pandora.com` (host-only `www.pandora.com` also accepted).
     */
    private fun existingCookie(): String? =
        AmphoraHttp.cookiesFor(webRootURL)
            .firstOrNull { cookie ->
                cookie.name == "csrftoken" &&
                    (
                        cookie.domain == "www.pandora.com" ||
                            cookie.domain == "pandora.com" ||
                            cookie.domain.endsWith(".pandora.com")
                        )
            }?.value

    private fun read(): String? = synchronized(lock) { cached }

    private fun store(value: String) = synchronized(lock) { cached = value }

    private fun clear() = synchronized(lock) { cached = null }
}

