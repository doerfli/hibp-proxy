package li.doerf

import com.codahale.metrics.Slf4jReporter
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.*
import io.ktor.http.*
import io.ktor.metrics.dropwizard.*
import io.ktor.response.*
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger("Application")
val dotenv = dotenv{
    ignoreIfMalformed = true
    ignoreIfMissing = true
}
// the set that contains all active (pending) account_deviceid combinations
val bgWorkerQueue = Collections.synchronizedSet(mutableSetOf<String>())
// time the last ping request was received by the bgworker
var lastPing: Instant = Instant.MIN

fun main() {
    lateinit var bgworker: SendChannel<ProxyRequest>
    GlobalScope.launch {
        bgworker = createBgWorker()
    }

    val port = dotenv.get("PORT", "8080").toInt()
    logger.info("starting server on port $port")
    val server = embeddedServer(Netty, port) {
        install(ContentNegotiation) {
            gson {
            }
            install(StatusPages) {
                exception<IllegalArgumentException> { cause ->
                    logger.warn("caught IllegalArgumentException", cause)
                    call.respond(HttpStatusCode.BadRequest)
                }
                exception<Throwable> { cause ->
                    logger.error("caught Throwable", cause)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
            install(DropwizardMetrics) {
                Slf4jReporter.forRegistry(registry)
                    .outputTo(logger)
                    .convertRatesTo(TimeUnit.MINUTES)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .build()
                    .start(5, TimeUnit.MINUTES)
            }
        }

        routing {
            get("/") {
                call.respondText("nothing to see here. please go away", contentType = ContentType.Text.Plain)
            }
            get("/search") {
                val userAgent = call.request.headers["user-agent"]
                logger.info("/search received request (UserAgent: $userAgent)")

                val account = call.request.queryParameters["account"] ?: throw IllegalArgumentException("account empty")
                val deviceToken = call.request.queryParameters["device_token"] ?: throw IllegalArgumentException("device_token empty")
                val timestamp = call.request.headers["x-hacked-now"]
                val reqToken = call.request.headers["x-hacked-requestToken"]

                if (tokenValid(reqToken, account, timestamp, deviceToken)) {
                    dispatchProxyRequest(account, deviceToken, bgworker, port = port)
                }

                call.respondText("enqueued request for $deviceToken", contentType = ContentType.Text.Plain)
            }

            get("/monitor") {
                logger.info("/monitor received request")
                dispatchProxyRequest("dummy", "dummy", bgworker, true, port)
                delay(20) // wait for background request to go through (at least in most cases)
                val tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES)
                logger.debug("/monitor lastPing:      ${lastPing.toHttpDateString()}")
                logger.debug("/monitor tenMinutesAgo: ${tenMinutesAgo.toHttpDateString()}")
                val status = if (lastPing.isBefore(tenMinutesAgo)) {
                        logger.debug("/monitor HttpStatusCode.InternalServerError")
                        HttpStatusCode.InternalServerError
                    } else {
                    logger.debug("/monitor HttpStatusCode.OK")
                        HttpStatusCode.OK
                    }
                call.respond(status, mapOf("lastPing" to lastPing.getEpochSecond()))
            }

            post("/ping") {
                logger.info("/ping received request")
                lastPing = Instant.now()
                logger.debug("/ping lastPing: ${lastPing.toHttpDateString()}")
                call.respond(HttpStatusCode.OK, mapOf("lastPing" to lastPing.getEpochSecond()))
            }
        }
    }
    server.start(wait = true)
}

fun tokenValid(reqToken: String?, account: String, timestamp: String?, deviceToken: String): Boolean {
    if (dotenv.get("NO_TOKEN_REQUIRED", "false").toBoolean()) {
        return true
    }

    var valid = false

    if (reqToken != null) {
        val expectedToken =
            String(Hex.encodeHex(DigestUtils.sha1("$account-$timestamp-$deviceToken}"))).toUpperCase(Locale.getDefault())
        valid = reqToken == expectedToken
        logger.trace("account: $account, timestamp: $timestamp, deviceToken: $deviceToken")
        logger.debug("token valid: $valid - received '$reqToken' / expected '$expectedToken'")
    } else {
        logger.debug("no request token received")
    }

    return valid
}

internal suspend fun dispatchProxyRequest(
    account: String,
    deviceToken: String,
    bgworker: SendChannel<ProxyRequest>,
    ping: Boolean = false,
    port: Int = 8080
) {
    val accountDevice = "${account}_$deviceToken"
    if (bgWorkerQueue.contains(accountDevice)) {  // already queued
        logger.warn("request already queued")
        return
    }
    withContext(Dispatchers.Default) {
        val r = ProxyRequest(UUID.randomUUID(), account, deviceToken, ping, port)
        logger.debug("sending proxy request ${r.requestId}")
        bgworker.send(r)
        bgWorkerQueue.add(accountDevice)
        logger.trace("request ${r.requestId} sent (queue size: ${bgWorkerQueue.size})")
    }
    if (bgWorkerQueue.size > 10) {
        logger.info("bg worker queue size: ${bgWorkerQueue.size}")
    }
}

