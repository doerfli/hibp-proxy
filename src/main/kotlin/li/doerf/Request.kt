package li.doerf

import java.util.*

sealed class Request

data class ProxyRequest(
    val requestId: UUID,
    val account: String,
    val deviceToken: String,
    // indicates a ping request to check bgworker is alive (will not lead to hibp proxy request)
    val ping: Boolean = false,
    val port: Int = 8080
) : Request()
