package li.doerf

import java.util.*

sealed class Request

data class ProxyRequest(
    val requestId: UUID,
    val account: String,
    val deviceToken: String,
    val ping: Boolean = false
) : Request()
