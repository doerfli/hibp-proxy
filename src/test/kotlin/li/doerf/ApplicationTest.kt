package li.doerf

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify


class ApplicationTest {

    /**
     * Returns ArgumentCaptor.capture() as nullable type to avoid java.lang.IllegalStateException
     * when null is returned.
     */
    fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()

    @Test
    fun testDispatchProxyRequest() {
        val account = "accoutName"
        val deviceToken = "deviceToken"
        val bgWorkerMock = mock(SendChannel::class.java) as SendChannel<ProxyRequest>
        val captor: ArgumentCaptor<ProxyRequest> = ArgumentCaptor.forClass(ProxyRequest::class.java)

        bgWorkerQueue.clear()
        assertTrue(bgWorkerQueue.isEmpty())

        runBlocking {
            dispatchProxyRequest(account, deviceToken, bgWorkerMock, false)

            assertThat(bgWorkerQueue.size).isEqualTo(1)
            verify(bgWorkerMock).send(capture(captor))

            val r = captor.value
            assertThat(r.account).isEqualTo(account)
            assertThat(r.deviceToken).isEqualTo(deviceToken)
            assertThat(r.requestId).isNotNull()
            assertThat(r.ping).isFalse()
        }
    }

    @Test
    fun testDispatchProxyRequestTwice() {
        val account = "accoutName"
        val deviceToken = "deviceToken"
        val bgWorkerMock = mock(SendChannel::class.java) as SendChannel<ProxyRequest>
        val captor: ArgumentCaptor<ProxyRequest> = ArgumentCaptor.forClass(ProxyRequest::class.java)

        bgWorkerQueue.clear()
        assertTrue(bgWorkerQueue.isEmpty())

        runBlocking {
            dispatchProxyRequest(account, deviceToken, bgWorkerMock, false)

            assertThat(bgWorkerQueue.size).isEqualTo(1)

            dispatchProxyRequest(account, deviceToken, bgWorkerMock, false)
            assertThat(bgWorkerQueue.size).isEqualTo(1)
        }
    }

    @Test
    fun testDispatchProxyRequestPing() {
        val account = "accoutName"
        val deviceToken = "deviceToken"
        val bgWorkerMock = mock(SendChannel::class.java) as SendChannel<ProxyRequest>
        val captor: ArgumentCaptor<ProxyRequest> = ArgumentCaptor.forClass(ProxyRequest::class.java)

        bgWorkerQueue.clear()
        assertTrue(bgWorkerQueue.isEmpty())

        runBlocking {
            dispatchProxyRequest(account, deviceToken, bgWorkerMock, true)

            assertThat(bgWorkerQueue.size).isEqualTo(1)
            verify(bgWorkerMock).send(capture(captor))

            val r = captor.value
            assertThat(r.ping).isTrue()
        }
    }
}