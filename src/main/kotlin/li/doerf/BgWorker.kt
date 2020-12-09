package li.doerf

import com.github.kittinunf.fuel.coroutines.awaitStringResponse
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit


private const val requestInterval = 1500L
private val apiKey = dotenv.get("HIBP_API_KEY", "xxxxx")
private val firebaseCredentials =  Base64.getDecoder().decode(dotenv["FIREBASE_CREDENTIALS"])
private val logger: Logger = LoggerFactory.getLogger("BgWorker")
private var nextRequestAfter = Instant.now()


fun initializeFirebaseApp() {
    val options = FirebaseOptions.Builder()
        .setCredentials(GoogleCredentials.fromStream(ByteArrayInputStream(firebaseCredentials)))
        .build()
    FirebaseApp.initializeApp(options)
}

fun CoroutineScope.createBgWorker(): SendChannel<ProxyRequest> = actor(capacity = 500) {
    logger.info("BgWorker starting")
    logger.trace("hibp api key: $apiKey")
    logger.trace(String(firebaseCredentials))
    initializeFirebaseApp()

    for (msg in channel) {
        logger.info("proxy request received ${msg.requestId}")
        logger.trace("$msg")
        try {
            if (isPing(msg)) continue  // handle ping request and continue
            doProxyRequestWithRetries(msg)
        } finally {
            val accountDevice = "${msg.account}_${msg.deviceToken}"
            bgWorkerQueue.remove(accountDevice)
            logger.info("request finished ${msg.requestId} (remaining queue size: ${bgWorkerQueue.size})")
        }
    }
    logger.info("BgWorker exiting")
}

private suspend fun doProxyRequestWithRetries(msg: ProxyRequest) {
    var retry = 3
    do {
        try {
            processProxyRequest(msg)
            retry = 0  // finished
        } catch (e: TooManyRequestsException) {
            logger.warn("retry after $nextRequestAfter")
        } catch (e: IOException) {
            retry--
            nextRequestAfter = nextRequestAfter.plus(5, ChronoUnit.SECONDS)
            logger.warn("caught IOException, retrying in 5 seconds", e)
        } catch (e: Exception) {
            retry--
            logger.error("unexpected exception", e)
        }
    } while (retry > 0)
}

private suspend fun processProxyRequest(request: ProxyRequest) {
    delayIfRequired()
    val (pwned, hibpResponse) = isPwned(request.account)
    logger.trace("account (${request.account}) pwned? $pwned")
    notifyDevice(request.deviceToken, request.account, hibpResponse)
}

private suspend fun isPing(x: ProxyRequest): Boolean {
    if (x.ping) {
        val (_, response, _) = "http://localhost:${x.port}/ping".httpPost().awaitStringResponse()
        logger.info("processed ping request. response status code: ${response.statusCode}")
        return true
    }
    return false
}

suspend fun delayIfRequired() {
    logger.debug("next request after: $nextRequestAfter - now: ${Instant.now()}")
    val requestAllowed = Instant.now().isAfter(nextRequestAfter)
    if (!requestAllowed) {
        val sleepFor = nextRequestAfter.toEpochMilli() - Instant.now().toEpochMilli()
        logger.debug("sleeping for ${sleepFor}ms")
        delay(sleepFor)
    }
}

suspend fun isPwned(account: String): Pair<Boolean, String> {
    val accountUrlEncoded = URLEncoder.encode(account, Charset.defaultCharset())
    val url = "https://haveibeenpwned.com/api/v3/breachedaccount/$accountUrlEncoded"
    logger.info("sending request to haveibeenpwned.com")
    val (_, response, _) =
        url.httpGet()
            .header("Hibp-Api-Key", apiKey)
            .header("UserAgent", "hibp-proxy_for_hacked_android_app")
            .awaitStringResponseResult()
    nextRequestAfter = Instant.now().plusMillis(requestInterval)
    logger.info("response status code: ${response.statusCode}")
    if (response.statusCode == HttpStatusCode.TooManyRequests.value) {
        val retryAfter = response.header("retry-after").first().toLong()
        logger.warn("received 429 - retry after ${retryAfter}s")
        nextRequestAfter = Instant.now().plus(retryAfter, ChronoUnit.SECONDS)
        throw TooManyRequestsException()
    }
    val responseString = response.body().asString("application/json")
    logger.trace("hibp response: $responseString")
    val success = response.statusCode == HttpStatusCode.OK.value
    return Pair(success, if (success) { responseString } else { "[]" })
}

fun notifyDevice(deviceToken: String, account: String, response: String) {
    logger.trace("building fcm message")
    val message = Message.builder()
        .putData("account", account)
        .putData("type", "hibp-response")
        .putData("response", response)
        .setToken(deviceToken)
        .build()

    logger.debug("sending fcm response")
    val fcmResponse = FirebaseMessaging.getInstance().sendAsync(message)[10, TimeUnit.SECONDS]
    logger.info("sent fcm message: $fcmResponse")
}
