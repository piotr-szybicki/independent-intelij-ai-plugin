package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_RETRIES = 10
private const val MAX_RETRY_DELAY_MILLIS = 60_000L

/** Returns null when retrying would exceed the attempt or wait limit. */
internal fun rateLimitRetryDelay(
    retryAfter: String?,
    retryCount: Int,
    now: Instant = Instant.now(),
): Long? {
    if (retryCount !in 0 until MAX_RETRIES) return null
    val value = retryAfter?.trim().orEmpty()
    val seconds = value.toLongOrNull()
    val delay =
        when {
            seconds != null && seconds >= 0 -> {
                if (seconds > MAX_RETRY_DELAY_MILLIS / 1000) return null
                seconds * 1000
            }
            else -> {
                val date =
                    runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
                        .getOrNull()
                if (date == null) 0L else Duration.between(now, date).toMillis().coerceAtLeast(0)
            }
        }
    val backoff = (1000L shl retryCount).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    return maxOf(delay, backoff).takeIf { it <= MAX_RETRY_DELAY_MILLIS }
}
