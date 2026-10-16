package io.agentspace.quest.terminal
import io.agentspace.quest.connection.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class ObservationRetryTest {
    @Test fun retriesOnlyTransportInterruptions() {
        assertTrue(retryableObservation(IOException("EOF")))
        assertTrue(retryableObservation(ConnectionException(ConnectionFailure.NETWORK, "offline")))
        assertFalse(retryableObservation(ObserveProtocolException("bad frame")))
        for (reason in ConnectionFailure.entries.filter { it != ConnectionFailure.NETWORK })
            assertFalse(retryableObservation(ConnectionException(reason, "stop")))
        assertFalse(retryableObservation(null))
    }
    @Test fun backoffIsBounded() {
        assertEquals(listOf(2000L,4000L,8000L,16000L,30000L,30000L), (0..5).map(::observationRetryDelay))
        assertEquals(30000L, observationRetryDelay(Int.MAX_VALUE))
    }
}
