package io.agentspace.quest.terminal

import io.agentspace.quest.connection.ConnectionException
import io.agentspace.quest.connection.ConnectionFailure
import java.io.IOException

internal fun retryableObservation(error: Exception?): Boolean = when (error) {
    is ObserveProtocolException -> false
    is ConnectionException -> error.reason == ConnectionFailure.NETWORK
    is IOException -> true
    else -> false
}
internal fun observationRetryDelay(attempt: Int): Long = (2000L shl attempt.coerceIn(0, 4)).coerceAtMost(30000L)
