package com.shiraka.locatiobprovid.utils

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex

/** Serializes every in-process writer that targets the shared runtime files. */
object RuntimeConfigWriteCoordinator {
    // Seed with boot-monotonic process start time so a restarted host process cannot
    // collide with a still-cached generation inside long-lived system/GMS hooks.
    private val nextGeneration = AtomicLong(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L))
    private val mutex = Mutex()
    private var lastCommittedGeneration = 0L

    fun newGeneration(): Long = nextGeneration.incrementAndGet()

    suspend fun commitIfLatest(
        generation: Long,
        write: suspend (Long) -> Boolean
    ): Boolean {
        mutex.lock()
        try {
            if (generation < lastCommittedGeneration) return false
            // Reserving a newer generation must not invalidate a valid writer:
            // that newer coroutine may be cancelled before it reaches this lock.
            // Only a generation that actually committed can supersede another.
            if (!write(generation)) return false
            lastCommittedGeneration = generation
            return true
        } finally {
            mutex.unlock()
        }
    }
}
