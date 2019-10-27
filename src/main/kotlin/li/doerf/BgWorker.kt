package li.doerf

import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.github.kittinunf.fuel.httpGet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import org.slf4j.LoggerFactory
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.Application
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import org.slf4j.Logger
import java.lang.IllegalStateException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.Instant
import java.time.temporal.ChronoUnit

private val apiKey = dotenv().get("HIBP_API_KEY", "xxxxx")
private val requestInterval = 1500L
private val firebaseProjectId = dotenv().get("FIREBASE_PROJECT_ID")
private var nextRequestAfter = Instant.now()

private val logger: Logger = LoggerFactory.getLogger(Application::class.java)


fun CoroutineScope.createBgWorker(): SendChannel<ProxyRequest> = actor(capacity = 100) {
    logger.info("BgWorker starting")
//    logger.trace("hibp api key: $apiKey")

    for (x in channel) {
        logger.debug("proxy request received ${x.reqId}")
        do {
            var retry = false
            try {
                delayIfRequired()
                val pwned = isPwned(x.account)
                logger.trace("account (${x.account}) pwned? ${pwned}")
            } catch (e: TooManyRequestsException) {
                retry = true
                logger.warn("retry after $nextRequestAfter")
            }
        } while (retry)
    }
    logger.info("BgWorker exiting")
}

suspend fun delayIfRequired() {
    logger.debug("next request after: $nextRequestAfter - now: ${Instant.now()}")
    val requestAllowed = Instant.now().isAfter(nextRequestAfter)
    logger.debug(requestAllowed.toString())
    if (!requestAllowed) {
        val sleepFor = nextRequestAfter.toEpochMilli() - Instant.now().toEpochMilli()
        logger.debug("sleeping for ${sleepFor}ms")
        delay(sleepFor)
    }
}

suspend fun isPwned(account: String): Boolean {
    val accountUrlEncoded = URLEncoder.encode(account, Charset.defaultCharset())
    val url = "https://haveibeenpwned.com/api/v3/breachedaccount/$accountUrlEncoded"
    logger.debug("sending request to haveibeenpwned.com")
    val (request, response, result) =
        url.httpGet()
            .header("Hibp-Api-Key", apiKey)
            .header("UserAgent", "hibp-proxy_for_hacked_android_app")
            .awaitStringResponseResult()
    nextRequestAfter = Instant.now().plusMillis(requestInterval)
    logger.debug("response status code: ${response.statusCode}")
    if (response.statusCode == HttpStatusCode.TooManyRequests.value) {
        val retryAfter = response.header("retry-after").first().toLong()
        logger.warn("received 429 - retry after ${retryAfter}s")
        nextRequestAfter = Instant.now().plus(retryAfter, ChronoUnit.SECONDS)
        throw TooManyRequestsException()
    }
    return response.statusCode == HttpStatusCode.OK.value
}
