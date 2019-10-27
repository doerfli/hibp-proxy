package li.doerf

import java.util.*

sealed class Request

data class ProxyRequest(val reqId: UUID, val account: String, val device_token: String) : Request()
