package dev.brahmkshatriya.echo.extension.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.ConnectException


object RetryUtils {
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        initialDelay: Long = 1000L,
        maxDelay: Long = 10000L,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(maxRetries - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                
                if (!isRetriableError(e)) {
                    println("Non-retriable error, not retrying: ${e.message}")
                    throw e
                }
                
                println("Retry ${attempt + 1}/$maxRetries after ${currentDelay}ms: ${e.message}")
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }
        return block()
    }
    
    private fun isRetriableError(e: Exception): Boolean = when {
        e is SocketTimeoutException -> true
        e is ConnectException -> true
        e is IOException && e.message?.contains("timeout", ignoreCase = true) == true -> true
        e is IOException && e.message?.contains("reset", ignoreCase = true) == true -> true
        e.message?.contains("5xx", ignoreCase = true) == true -> true
        e.message?.contains("503", ignoreCase = true) == true -> true
        e.message?.contains("502", ignoreCase = true) == true -> true
        e.message?.contains("504", ignoreCase = true) == true -> true
        else -> false
    }
}
