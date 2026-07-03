package li.doerf

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
private val firebaseCredentials = Base64.getDecoder().decode(dotenv["FIREBASE_CREDENTIALS"])
private val logger: Logger = LoggerFactory.getLogger("BgWorker")
private var nextRequestAfter = Instant.now()
private val httpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 10_000
    }
}


fun initializeFirebaseApp() {
    val options = FirebaseOptions.Builder()
        .setCredentials(GoogleCredentials.fromStream(ByteArrayInputStream(firebaseCredentials)))
        .build()
    FirebaseApp.initializeApp(options)
}

fun CoroutineScope.createBgWorker(): SendChannel<ProxyRequest> {
    val channel = Channel<ProxyRequest>(500)
    launch {
        logger.info("BgWorker starting")
        logger.trace("hibp api key: $apiKey")
        logger.trace(String(firebaseCredentials))
        initializeFirebaseApp()

        for (msg in channel) {
            lastPing = Instant.now()
            logger.info("proxy request received ${msg.requestId}")
            logger.trace("$msg")
            try {
                if (msg.ping) {
                    continue
                }
                doProxyRequestWithRetries(msg)
            } finally {
                val accountDevice = "${msg.account}_${msg.deviceToken}"
                bgWorkerQueue.remove(accountDevice)
                logger.info("request finished ${msg.requestId} (remaining queue size: ${bgWorkerQueue.size})")
            }
        }
        logger.info("BgWorker exiting")
    }
    return channel
}

private suspend fun doProxyRequestWithRetries(msg: ProxyRequest) {
    try {
        withTimeout(30_000) {
            var retry = 3
            do {
                try {
                    processProxyRequest(msg)
                    retry = 0
                } catch (e: TooManyRequestsException) {
                    retry--
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
    } catch (e: TimeoutCancellationException) {
        logger.warn("giving up on request ${msg.requestId} after 30s", e)
    }
}

private suspend fun processProxyRequest(request: ProxyRequest) {
    delayIfRequired()
    val (pwned, hibpResponse) = isPwned(request.account)
    logger.trace("account (${request.account}) pwned? $pwned")
    notifyDevice(request.deviceToken, request.account, hibpResponse)
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
    val response = httpClient.get(url) {
        headers {
            append("Hibp-Api-Key", apiKey)
            append("User-Agent", "hibp-proxy_for_hacked_android_app")
            append("Hibp-Version", "1.0")
        }
    }
    nextRequestAfter = Instant.now().plusMillis(requestInterval)
    logger.info("response status code: ${response.status.value}")
    if (response.status.value == HttpStatusCode.TooManyRequests.value) {
        val retryAfter = response.headers["retry-after"]?.toLong() ?: 60L
        logger.warn("received 429 - retry after ${retryAfter}s")
        nextRequestAfter = Instant.now().plus(retryAfter, ChronoUnit.SECONDS)
        throw TooManyRequestsException()
    }
    val responseString = response.bodyAsText()
    logger.trace("hibp response: $responseString")
    val success = response.status == HttpStatusCode.OK
    return Pair(success, if (success) responseString else "[]")
}

suspend fun notifyDevice(deviceToken: String, account: String, response: String) {
    logger.trace("building fcm message")
    val message = Message.builder()
        .putData("account", account)
        .putData("type", "hibp-response")
        .putData("response", response)
        .setToken(deviceToken)
        .build()

    logger.debug("sending fcm response")
    val fcmResponse = withContext(Dispatchers.IO) {
        FirebaseMessaging.getInstance().sendAsync(message)[10, TimeUnit.SECONDS]
    }
    logger.info("sent fcm message: $fcmResponse")
}
