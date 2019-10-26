package li.doerf

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import org.slf4j.LoggerFactory

fun main(args: Array<String>): Unit {
    lateinit var bgworker: SendChannel<String>
    GlobalScope.launch {
        bgworker = createBgWorker()
    }

    // TODO get port via env var (heroku)
    val server = embeddedServer(Netty, 8080) {
        install(ContentNegotiation) {
            gson {
            }
        }

        routing {
            get("/") {
                withContext(Dispatchers.Default) {
                    bgworker.send("bla")
                    LoggerFactory.getLogger(this.javaClass).debug("sent")
                }

                call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
            }

            get("/json/gson") {
                call.respond(mapOf("hello" to "world"))
            }
        }
    }
    server.start(wait = true)
}

fun CoroutineScope.createBgWorker(): SendChannel<String> = actor() {
    println("    Output starting")
    for (x in channel) {
        LoggerFactory.getLogger(this.javaClass).debug("Output received $x")
        val sleep: Long = (1000..6000).random().toLong()
        withContext(Dispatchers.IO) {
            Thread.sleep(sleep)
        }
        LoggerFactory.getLogger(this.javaClass).debug("sleep done")
    }
    println("    Output exiting")
}

//@Suppress("unused") // Referenced in application.conf
//@kotlin.jvm.JvmOverloads
//fun Application.module(testing: Boolean = false) {
//    install(ContentNegotiation) {
//        gson {
//        }
//    }
//
//    routing {
//        get("/") {
//            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
//        }
//
//        get("/json/gson") {
//            call.respond(mapOf("hello" to "world"))
//        }
//    }
//}

