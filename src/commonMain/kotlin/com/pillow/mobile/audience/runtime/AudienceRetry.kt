package com.pillow.mobile.audience.runtime

import kotlin.math.min
import kotlin.random.Random

internal fun computeNextAttemptEpochMs(
  nowEpochMs: Long,
  attemptCount: Long,
): Long {
  val cappedAttempt = min(attemptCount.toInt(), 8)
  val baseDelayMs = 2_000L * (1L shl cappedAttempt)
  val boundedDelayMs = min(baseDelayMs, 300_000L)
  val jitterWindow = (boundedDelayMs * 0.2).toLong()
  val jitter = if (jitterWindow > 0L) Random.nextLong(0L, jitterWindow + 1L) else 0L
  return nowEpochMs + boundedDelayMs + jitter
}
