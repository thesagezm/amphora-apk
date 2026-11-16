//
//  OkHttpTransports.kt
//  app
//
//  The app-side transport adapters over one shared OkHttpClient — the
//  Kotlin counterparts of the iOS URLSession transports (which lived
//  inside PandoraCore on Apple platforms). `:core` stays client-free;
//  every adapter here maps raw IO failures onto the sanitized reason
//  strings the typed errors' user-facing copy knows:
//  "timeout" / "unreachable" / "network failure" (the port of the
//  URLError classification) / "non-HTTP response" / "HTTP <status>".
//

package dev.personal.amphora.pandora

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import pandoracore.HttpGetTransport
import pandoracore.HttpTransport
import pandoracore.LastFmError
import pandoracore.LastFmHTTPResponse
import pandoracore.LastFmTransport
import pandoracore.ModesHTTPResponse
import pandoracore.ModesTransport
import pandoracore.NetworkDefaults
import pandoracore.PandoraTransportError
import pandoracore.RadioBrowserError
import pandoracore.RadioBrowserTransport
import pandoracore.TransportRequest

/** The reason classification the iOS URLError path provided: a timeout
 *  is not offline, offline is not a generic failure — the UI copy
 *  distinguishes all three. */
fun classifyIOException(error: IOException): String = when (error) {
    is SocketTimeoutException -> "timeout"
    is UnknownHostException, is ConnectException -> "unreachable"
    else -> "network failure"
}

/**
 * One OkHttpClient for the whole app: persistent cookies (the modern
 * web API's `csrftoken` bootstrap rides on it), the shared request
 * timeout, and browser-like identification where a service asks for
 * it.
 */
object AmphoraHttp {
    /** In-memory cookie jar — the csrftoken cookie survives across
     *  calls within a session, exactly like `HTTPCookieStorage.shared`
     *  did on iOS. */
    private val cookieJar = object : CookieJar {
        private val store = mutableMapOf<HttpUrl, List<Cookie>>()
        private val lock = Any()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(lock) { store[url] = cookies }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(lock) {
                val matched = store.entries
                    .filter { it.key.topPrivateDomain() == null || url.topPrivateDomain() == it.key.topPrivateDomain() || it.key.host == url.host }
                    .flatMap { it.value }
                    .filter { it.matches(url) }
                matched
            }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(NetworkDefaults.requestTimeoutInterval.toLong(), TimeUnit.SECONDS)
        .readTimeout(NetworkDefaults.requestTimeoutInterval.toLong(), TimeUnit.SECONDS)
        .callTimeout(NetworkDefaults.requestTimeoutInterval.toLong(), TimeUnit.SECONDS)
        .build()

    /** The cookies the jar holds for `url` (the CSRF provider reads the
     *  `csrftoken` cookie from here). */
    fun cookiesFor(url: String): List<Cookie> =
        cookieJar.loadForRequest(url.toHttpUrl())
}

/** Executes a `TransportRequest` and returns the body, mapping non-2xx
 *  and IO failures onto the sanitized transport reasons. */
fun OkHttpClient.post(request: TransportRequest): ByteArray {
    val call = newCall(
        Request.Builder()
            .url(request.url)
            .post(
                okhttp3.RequestBody.create(
                    null,
                    request.body,
                ),
            )
            .header("Content-Type", request.contentType)
            .header("User-Agent", request.userAgent)
            .build(),
    )
    call.timeout().timeout((request.timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    val response: Response = try {
        call.execute()
    } catch (error: IOException) {
        throw PandoraTransportError(classifyIOException(error))
    }
    response.use {
        if (it.code !in 200..299) {
            throw PandoraTransportError("HTTP ${it.code}")
        }
        return it.body.bytes()
    }
}

/** The Pandora JSON-RPC transport (Swift: `URLSessionTransport`). */
class OkHttpPandoraTransport(
    private val userAgent: String = TransportRequest.DEFAULT_USER_AGENT,
) : HttpTransport {
    override suspend fun post(url: String, body: ByteArray, contentType: String): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AmphoraHttp.client.post(
                TransportRequest.make(
                    url = url,
                    body = body,
                    contentType = contentType,
                    userAgent = userAgent,
                ),
            )
        }
}

/** The minimal GET transport (Swift: `URLSessionGETTransport`). */
class OkHttpGetTransport : HttpGetTransport {
    override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            for ((field, value) in headers) builder.header(field, value)
            val response = try {
                AmphoraHttp.client.newCall(builder.build()).execute()
            } catch (error: IOException) {
                throw PandoraTransportError(classifyIOException(error))
            }
            response.use {
                if (it.code !in 200..299) {
                    throw PandoraTransportError("HTTP ${it.code}")
                }
                it.body.bytes()
            }
        }
}

/** A POST with its raw status (Swift: `URLSessionModesTransport`). */
class OkHttpModesTransport : ModesTransport {
    override suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ModesHTTPResponse =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = Request.Builder().url(url).post(okhttp3.RequestBody.create(null, body))
            for ((field, value) in headers) builder.header(field, value)
            val response = try {
                AmphoraHttp.client.newCall(builder.build()).execute()
            } catch (error: IOException) {
                throw PandoraTransportError(classifyIOException(error))
            }
            response.use { ModesHTTPResponse(statusCode = it.code, body = it.body.bytes()) }
        }
}

/** The radio-directory GET transport (Swift: `RadioURLSessionTransport`
 *  — the directory pings use a shorter 15 s timeout by design). */
class OkHttpRadioBrowserTransport(
    private val userAgent: String = "Amphora/0.1 (personal)",
) : RadioBrowserTransport {
    override suspend fun get(url: String): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val call = AmphoraHttp.client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", userAgent)
                    .build(),
            )
            call.timeout().timeout(15_000, TimeUnit.MILLISECONDS)
            val response = try {
                call.execute()
            } catch (error: IOException) {
                throw RadioBrowserError.NetworkFailure
            }
            response.use {
                if (it.code !in 200..299) {
                    throw RadioBrowserError.HttpStatus(it.code)
                }
                it.body.bytes()
            }
        }
}

/** The Last.fm form-POST transport (Swift: `URLSessionLastFmTransport`). */
class OkHttpLastFmTransport : LastFmTransport {
    override suspend fun post(url: String, form: ByteArray, headers: Map<String, String>): LastFmHTTPResponse =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = Request.Builder().url(url).post(okhttp3.RequestBody.create(null, form))
            for ((field, value) in headers) builder.header(field, value)
            val response = try {
                AmphoraHttp.client.newCall(builder.build()).execute()
            } catch (error: IOException) {
                throw LastFmError.Transport("network failure")
            }
            response.use { LastFmHTTPResponse(statusCode = it.code, body = it.body.bytes()) }
        }
}
