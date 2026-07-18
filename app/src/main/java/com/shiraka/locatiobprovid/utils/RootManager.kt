package com.shiraka.locatiobprovid.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

class RootManager {

    private data class PersistentSession(
        val process: Process,
        val writer: java.io.BufferedWriter,
        val output: LinkedBlockingQueue<String>
    )

    private val persistentCommandMutex = Mutex()
    private val persistentStateLock = Any()
    private val persistentMarkerCounter = AtomicLong(0L)
    @Volatile private var persistentSession: PersistentSession? = null

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("id").contains("uid=0(root)")
    }

    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        var processToTerminate: Process? = null
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            processToTerminate = process
            val output = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (output.length < MAX_CAPTURED_OUTPUT_CHARS) {
                                output.appendLine(line.take(MAX_CAPTURED_OUTPUT_CHARS - output.length))
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "LocationSpoofer_RootOutput"
                start()
            }
            val deadlineNanos = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
            var completed = false
            while (!completed && System.nanoTime() < deadlineNanos) {
                ensureActive()
                completed = process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
            if (!completed) {
                terminateProcess(process)
                readerThread.join(1_000L)
                logRootFailure("one-shot timeout", output.toString())
                return@withContext "ERROR"
            }
            readerThread.join(1_000L)
            processToTerminate = null
            if (process.exitValue() != 0) {
                logRootFailure(
                    "one-shot exit=${process.exitValue()}",
                    output.toString()
                )
                "ERROR"
            } else {
                output.toString().trim().ifEmpty { "SUCCESS" }
            }
        } catch (e: CancellationException) {
            processToTerminate?.let(::terminateProcess)
            throw e
        } catch (e: Exception) {
            processToTerminate?.let(::terminateProcess)
            logRootFailure("one-shot exception=${e.javaClass.simpleName}", e.message.orEmpty())
            "ERROR"
        }
    }

    private fun logRootFailure(reason: String, output: String) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastRootFailureLogAt.get()
            if (previous != 0L && now - previous < ROOT_FAILURE_LOG_INTERVAL_MS) return
            if (lastRootFailureLogAt.compareAndSet(previous, now)) break
        }
        val detail = output.trim().takeLast(MAX_ROOT_FAILURE_LOG_CHARS)
        Log.e(
            "RootManager",
            if (detail.isEmpty()) "root command failed: $reason"
            else "root command failed: $reason; output=$detail"
        )
    }

    /**
     * Executes an active-runtime update through one service-owned root shell.
     * Commands remain strictly serialized and each one must acknowledge a unique
     * marker, avoiding a new su process and output-reader thread on every 1 Hz tick.
     */
    suspend fun executePersistentCommand(command: String): String =
        persistentCommandMutex.withLock {
            currentCoroutineContext().ensureActive()
            val result = withContext(Dispatchers.IO) {
                ensureActive()
                val session = getOrCreatePersistentSession() ?: return@withContext "ERROR"
                val marker = "__LOCSP_DONE_${persistentMarkerCounter.incrementAndGet()}__"
                try {
                    ensureActive()
                    // Execute directly in the persistent shell. Once flushed, wait
                    // non-cancellably for its marker so a later stop tombstone is
                    // guaranteed to commit after this command, never before an
                    // orphaned old child shell.
                    session.writer.append(command)
                    if (!command.endsWith('\n')) session.writer.append('\n')
                    session.writer.append("status=\$?\n")
                    session.writer.append("printf '\\n")
                    session.writer.append(marker)
                    session.writer.append(":%s\\n' \"\$status\"\n")
                    session.writer.flush()

                    withContext(NonCancellable) waitForMarker@{
                        val output = StringBuilder()
                        val deadlineNanos = System.nanoTime() +
                            TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
                        while (System.nanoTime() < deadlineNanos) {
                            val line = session.output.poll(
                                PROCESS_POLL_INTERVAL_MS,
                                TimeUnit.MILLISECONDS
                            )
                            if (line == null) {
                                if (!session.process.isAlive) {
                                    closePersistentSession(session)
                                    return@waitForMarker "ERROR"
                                }
                                continue
                            }
                            if (line == SESSION_CLOSED_MARKER) {
                                closePersistentSession(session)
                                return@waitForMarker "ERROR"
                            }
                            if (line.startsWith("$marker:")) {
                                val exitCode = line.substringAfter(':').trim().toIntOrNull()
                                return@waitForMarker if (exitCode == 0) {
                                    output.toString().trim().ifEmpty { "SUCCESS" }
                                } else {
                                    "ERROR"
                                }
                            }
                            if (output.length < MAX_CAPTURED_OUTPUT_CHARS) {
                                output.appendLine(
                                    line.take(MAX_CAPTURED_OUTPUT_CHARS - output.length)
                                )
                            }
                        }
                        closePersistentSession(session)
                        "ERROR"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    closePersistentSession(session)
                    "ERROR"
                }
            }
            currentCoroutineContext().ensureActive()
            result
        }

    /** Closes the active-only root shell. Safe to call repeatedly from stop/destroy paths. */
    fun closePersistentSession() {
        closePersistentSession(expected = null)
    }

    private fun getOrCreatePersistentSession(): PersistentSession? =
        synchronized(persistentStateLock) {
            persistentSession?.takeIf { it.process.isAlive }?.let { return@synchronized it }
            persistentSession?.let(::closePersistentSessionLocked)
            persistentSession = null
            try {
                val process = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                val output = LinkedBlockingQueue<String>()
                val readerThread = Thread {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line -> output.offer(line) }
                        }
                    } catch (_: java.io.IOException) {
                        // Destroying the active-only shell is the normal way to release
                        // this blocking read. Android reports that cross-thread fd close as
                        // InterruptedIOException; it must not escape as an app FATAL.
                    } finally {
                        output.offer(SESSION_CLOSED_MARKER)
                    }
                }.apply {
                    isDaemon = true
                    name = "LocationSpoofer_RootSessionOutput"
                }
                val session = PersistentSession(
                    process = process,
                    writer = process.outputStream.bufferedWriter(),
                    output = output
                )
                readerThread.start()
                session.also { persistentSession = it }
            } catch (_: Throwable) {
                null
            }
        }

    private fun closePersistentSession(expected: PersistentSession?) {
        val detached = synchronized(persistentStateLock) {
            val current = persistentSession ?: return
            if (expected != null && current !== expected) return
            persistentSession = null
            current
        }
        closePersistentSessionAsync(detached)
    }

    private fun closePersistentSessionLocked(session: PersistentSession) {
        closePersistentSessionAsync(session)
    }

    private fun closePersistentSessionAsync(session: PersistentSession) {
        Thread {
            runCatching { session.process.destroy() }
            if (runCatching { session.process.isAlive }.getOrDefault(false)) {
                runCatching { session.process.destroyForcibly() }
            }
            runCatching { session.writer.close() }
        }.apply {
            isDaemon = true
            name = "LocationSpoofer_RootSessionClose"
            start()
        }
    }

    private fun terminateProcess(process: Process) {
        runCatching { process.destroy() }
        if (runCatching { !process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) {
            runCatching { process.destroyForcibly() }
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 15L
        const val PROCESS_POLL_INTERVAL_MS = 200L
        const val MAX_CAPTURED_OUTPUT_CHARS = 16_384
        const val MAX_ROOT_FAILURE_LOG_CHARS = 2_000
        const val ROOT_FAILURE_LOG_INTERVAL_MS = 60_000L
        const val SESSION_CLOSED_MARKER = "__LOCSP_SESSION_CLOSED__"
        val lastRootFailureLogAt = AtomicLong(0L)
    }

    suspend fun cleanupRuntimeEnvironment(): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand(
            """
            sleep 2
            rm -f /data/local/tmp/locationspoofer_config.json
            rm -f /data/system/locationspoofer_config.json
            cmd location providers send-extra-command gps delete_aiding_data 2>/dev/null || true
            cmd location providers send-extra-command gps force_time_injection 2>/dev/null || true
            cmd location providers send-extra-command gps force_psds_injection 2>/dev/null || true
            cmd location set-location-enabled false 2>/dev/null || true
            sleep 1
            cmd location set-location-enabled true 2>/dev/null || true
            am force-stop com.google.android.gms 2>/dev/null || true
            am force-stop com.google.android.apps.maps 2>/dev/null || true
            """.trimIndent()
        )
        result != "ERROR"
    }
}
