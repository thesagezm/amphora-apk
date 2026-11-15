//
//  PandoraAPIClient.kt
//  pandoracore
//
//  Port of PandoraCore/PandoraAPIClient.swift.
//
//  The interface surface `LegacyPandoraClient` implements, so a modern-API
//  implementation can be swapped in later without touching the app.
//

package pandoracore

fun interface PandoraAPIClient {

    /**
     * Issues one JSON-RPC call against the legacy endpoint and returns the
     * decoded `result` object. Decoding into typed models happens above
     * this layer, so a swapped-in implementation only has to move bytes.
     */
    suspend fun request(
        method: String,
        parameters: Map<String, JSONValue>,
    ): Map<String, AnyJSON>
}
