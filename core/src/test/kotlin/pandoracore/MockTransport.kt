//
//  MockTransport.kt
//  pandoracore
//
//  Port of PandoraCoreTests/MockTransport.swift — shared scripted HTTP
//  transport: returns `responses` in call order, recording every request.
//  No network in any unit test.
//

package pandoracore

class MockTransport(
    private val responses: List<Result<ByteArray>>,
) : HttpTransport {

    data class RecordedRequest(
        val url: String,
        val body: ByteArray,
        val contentType: String,
    )

    private val lock = Any()
    private val recorded = mutableListOf<RecordedRequest>()
    private var next = 0

    val requests: List<RecordedRequest>
        get() = synchronized(lock) { recorded.toList() }

    override suspend fun post(url: String, body: ByteArray, contentType: String): ByteArray {
        val index = synchronized(lock) {
            recorded.add(RecordedRequest(url, body, contentType))
            next++
        }

        if (index >= responses.size) {
            throw PandoraTransportError("mock exhausted at request $index")
        }
        return responses[index].getOrThrow()
    }
}
