//
//  StubGETTransport.kt
//  pandoracore
//
//  Shared scripted GET transport for the GET-based clients (iTunes
//  artwork, lyrics, radio directory): returns `responses` in call order,
//  recording every request. No network in any unit test.
//

package pandoracore

class StubGETTransport(
    private val responses: List<Result<ByteArray>>,
) : HttpGetTransport {

    data class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    private val lock = Any()
    private val recorded = mutableListOf<RecordedRequest>()
    private var next = 0

    val requests: List<RecordedRequest>
        get() = synchronized(lock) { recorded.toList() }

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
        val index = synchronized(lock) {
            recorded.add(RecordedRequest(url, headers))
            next++
        }
        if (index >= responses.size) {
            throw PandoraTransportError("stub exhausted at request $index")
        }
        return responses[index].getOrThrow()
    }
}
