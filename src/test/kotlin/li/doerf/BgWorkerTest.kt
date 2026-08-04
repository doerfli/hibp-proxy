package li.doerf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class BgWorkerTest {

    /**
     * Regression test for the /monitor false alarm: while idle (no messages), the
     * worker must keep refreshing the [lastPing] heartbeat so it never goes stale.
     */
    @Test
    fun refreshesHeartbeatWhileIdle(): Unit = runBlocking {
        val channel = Channel<ProxyRequest>(10)
        val staleValue = Instant.now().minusSeconds(3600)
        lastPing = staleValue

        val job = launch(Dispatchers.Default) {
            runWorkerLoop(channel, heartbeatIntervalMs = 20L) { /* no-op */ }
        }

        withTimeout(2000) {
            while (lastPing == staleValue) delay(10)
        }

        assertThat(lastPing).isAfter(staleValue)
        job.cancel()
        channel.close()
    }

    /**
     * The idle-timeout heartbeat must never swallow an enqueued request: every
     * message sent across idle-timeout boundaries is processed and drained.
     */
    @Test
    fun processesEveryMessageAcrossIdleTicks(): Unit = runBlocking {
        bgWorkerQueue.clear()
        val channel = Channel<ProxyRequest>(10)
        val processed = Collections.synchronizedList(mutableListOf<UUID>())

        val job = launch(Dispatchers.Default) {
            runWorkerLoop(channel, heartbeatIntervalMs = 15L) { processed.add(it.requestId) }
        }

        val sent = (0 until 5).map { i ->
            val r = ProxyRequest(UUID.randomUUID(), "acc$i", "dev$i")
            bgWorkerQueue.add("acc${i}_dev$i")
            channel.send(r)
            delay(25) // cross an idle-timeout boundary between sends
            r.requestId
        }

        withTimeout(2000) {
            while (processed.size < sent.size) delay(10)
        }

        assertThat(processed).containsExactlyInAnyOrderElementsOf(sent)
        assertThat(bgWorkerQueue).isEmpty()
        job.cancel()
        channel.close()
    }
}
