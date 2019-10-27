package li.doerf

import com.codahale.metrics.Slf4jReporter
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
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import li.doerf.ProxyRequest
import li.doerf.createBgWorker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger(Application::class.java)

fun main(args: Array<String>): Unit {
    lateinit var bgworker: SendChannel<ProxyRequest>
    GlobalScope.launch {
        bgworker = createBgWorker()
    }

    // TODO get port via env var (heroku)
    val server = embeddedServer(Netty, 8080) {
        install(ContentNegotiation) {
            gson {
            }
            install(StatusPages) {
                // catch NumberFormatException and send back HTTP code 400
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
                logger.debug("received search request")
                val account = call.request.queryParameters["account"] ?: throw IllegalArgumentException("account empty")
                val deviceToken = call.request.queryParameters["device_token"] ?: throw IllegalArgumentException("device_token empty")

                dispatchProxyRequest(account, deviceToken, bgworker)

                call.respondText("enqueued request for $deviceToken", contentType = ContentType.Text.Plain)
            }

//            get("/json/gson") {
//                call.respond(mapOf("hello" to "world"))
//            }
        }
    }
    server.start(wait = true)
}

private suspend fun dispatchProxyRequest(
    account: String,
    deviceToken: String,
    bgworker: SendChannel<ProxyRequest>
) {
    withContext(Dispatchers.Default) {
        val r = ProxyRequest(UUID.randomUUID(), account, deviceToken)
        logger.debug("sending proxy request ${r.reqId}")
        bgworker.send(r)
        logger.trace("request ${r.reqId} sent")
    }
}

