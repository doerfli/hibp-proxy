package li.doerf

import com.codahale.metrics.Slf4jReporter
import io.github.cdimascio.dotenv.dotenv
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.metrics.dropwizard.DropwizardMetrics
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger(Application::class.java)
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
            get("/search") {
                logger.info("received search request")
                val account = call.request.queryParameters["account"] ?: throw IllegalArgumentException("account empty")
                val deviceToken = call.request.queryParameters["device_token"] ?: throw IllegalArgumentException("device_token empty")

                dispatchProxyRequest(account, deviceToken, bgworker, port = port)

                call.respondText("enqueued request for $deviceToken", contentType = ContentType.Text.Plain)
            }

            get("/monitor") {
                logger.info("received monitor request")
                dispatchProxyRequest("dummy", "dummy", bgworker, true, port)
                val status = if (lastPing.isBefore(Instant.now().minus(10, ChronoUnit.MINUTES))) {
                        HttpStatusCode.InternalServerError
                    } else {
                        HttpStatusCode.OK
                    }
                call.respond(status, mapOf("lastPing" to lastPing))
            }

            post("/ping") {
                logger.info("ping request")
                lastPing = Instant.now()
                call.respond(HttpStatusCode.OK, mapOf("lastPing" to lastPing))
            }
        }
    }
    server.start(wait = true)
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

