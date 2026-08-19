/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single owner for standard input.
 *
 * The CLI server had two readers on the same stdin: the command loop
 * (q, v 50, s...) and the ASK mode prompt. Whichever reads first consumes
 * the line, and since the command loop is always waiting, the authorization `y`
 * ended up there and was discarded as an unknown command, while the prompt
 * remained hung indefinitely — along with the network thread that invoked it.
 *
 * Here, there is only one reader. When a prompt is active, input lines go to
 * the prompt; otherwise, they go to the command handler. Prompts are serialized
 * among themselves, so two clients connecting concurrently queue up instead of
 * interleaving on the same terminal line.
 */

object ConsoleInput {

    /**
     * Without a terminal (systemd, pipes, double-click) there is no one to
     * prompt: this must be verified up front, not discovered by getting
     * blocked on a `readLine` that will never return.
     */
    val hasTty: Boolean by lazy { System.console() != null }

    private val promptLock = ReentrantLock()
    private val pending = AtomicReference<ArrayBlockingQueue<String>?>(null)

    @Volatile private var handler: ((String) -> Unit)? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /**
     * Starts the reader. [onLine] receives lines that no prompt is
     * waiting for; it can be null for prompt-only usage.
     */
    @Synchronized
    fun start(onLine: ((String) -> Unit)? = null) {
        handler = onLine
        if (running) return
        running = true
        thread = Thread {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            try {
                while (running) {
                    val line = reader.readLine() ?: break
                    val queue = pending.get()
                    if (queue != null) {
                        // offer, not put: if the prompt has already timed out,
                        // the line is dropped instead of blocking the reader.
                        queue.offer(line)
                    } else {
                        handler?.invoke(line.trim())
                    }
                }
            } catch (_: Exception) {
                // stdin closed: not an error, simply no one is
                // writing anymore.
            }
        }.also {
            it.isDaemon = true
            it.name = "wfas-console-input"
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        handler = null
        pending.set(null)
        runCatching { thread?.interrupt() }
        thread = null
    }

    /** Changes the command handler without stopping the reader. */
    fun setHandler(onLine: ((String) -> Unit)?) { handler = onLine }

    /**
     * Yes/no prompt on the terminal.
     *
     * @param timeoutMs after which [default] is applied. Zero or less = wait
     *   indefinitely, which only makes sense when an interactive user is present.
     * @param default fallback answer used when there is no terminal, when time
     *   runs out, or when the user simply presses Enter. For a security
     *   decision, this must be `false`: non-response does not grant authorization.
     */
    fun askYesNo(question: String, timeoutMs: Long, default: Boolean = false): Boolean {
        if (!hasTty) {
            System.err.println("  !  $question -> no terminal to ask on, answering ${if (default) "yes" else "no"}.")
            return default
        }

        return promptLock.withLock {
            start(handler)
            val queue = ArrayBlockingQueue<String>(4)
            pending.set(queue)
            var answer = default
            try {
                System.err.print("$question ")
                System.err.flush()

                val deadline =
                    if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
                var waiting = true
                while (waiting) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (timeoutMs > 0 && remaining <= 0) {
                        System.err.println()
                        System.err.println(
                            "  !  No answer within ${timeoutMs / 1000}s, answering " +
                                (if (default) "yes" else "no") + "."
                        )
                        waiting = false
                        continue
                    }
                    val slice = if (timeoutMs > 0) remaining.coerceAtMost(500L) else 500L
                    val line = queue.poll(slice, TimeUnit.MILLISECONDS) ?: continue
                    when (line.trim().lowercase()) {
                        "y", "yes", "s", "si", "s\u00ec" -> { answer = true;    waiting = false }
                        "n", "no"                    -> { answer = false;   waiting = false }
                        ""                           -> { answer = default; waiting = false }
                        else -> {
                            System.err.print("  Please answer y or n: ")
                            System.err.flush()
                        }
                    }
                }
                answer
            } finally {
                pending.set(null)
            }
        }
    }
}
