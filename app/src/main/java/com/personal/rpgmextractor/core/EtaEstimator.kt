package com.personal.rpgmextractor.core

/**
 * Estimates remaining time from the throughput over a recent sliding window rather than
 * since the start, so it adapts when speed changes (e.g. big videos vs. tiny sprites).
 * Call [reset] on pause so paused time isn't counted as slow progress.
 */
class EtaEstimator(private val windowMs: Long = 15_000) {
    private val samples = ArrayDeque<Pair<Long, Int>>()

    @Synchronized
    fun reset() = samples.clear()

    /** Returns the estimated milliseconds remaining, or -1 while there isn't enough data yet. */
    @Synchronized
    fun update(nowMs: Long, done: Int, total: Int): Long {
        samples.addLast(nowMs to done)
        while (samples.size > 2 && nowMs - samples.first().first > windowMs) samples.removeFirst()
        val (t0, d0) = samples.first()
        val elapsed = nowMs - t0
        val progressed = done - d0
        if (elapsed < 2_000 || progressed <= 0) return -1
        return (total - done).toLong() * elapsed / progressed
    }
}

/** "1h 05m", "3m 20s", "45s"; null when no estimate is available yet. */
fun formatEta(ms: Long): String? {
    if (ms < 0) return null
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
    }
}
