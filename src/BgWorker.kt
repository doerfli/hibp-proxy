package li.doerf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

fun CoroutineScope.createBgWorker(): SendChannel<ProxyRequest> = actor(capacity = 100) {
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