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

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The "can this client join?" terminal prompt in ASK mode.
 *
 * Three issues that previously failed, which form the entire rationale
 * for this class:
 *
 * 1. **Single stdin reader.** The answer goes through [ConsoleInput], which is
 *    the sole owner of the input: previously, the server command loop
 *    consumed the `y` and the prompt hung indefinitely.
 *
 * 2. **One prompt per client, not per packet.** While awaiting approval, the
 *    client retries HELLO every few hundred milliseconds, and each HELLO triggered
 *    a new prompt. Here, the first request opens the prompt and all subsequent
 *    retries wait for *that* single response.
 *
 * 3. **Silence means deny.** Timeouts, absence of a terminal, or `--json` output
 *    modes cannot default to "let's see": the answer is no, and it is explicitly logged.
 */
class CliAuthPrompt(
    private val timeoutMs: Long,
    private val jsonMode: Boolean,
    private val visualizer: AudioVisualizer?,
    private val log: (String) -> Unit
) {

    private sealed class Entry {
        class Pending(val gate: ArrayBlockingQueue<Boolean> = ArrayBlockingQueue(1)) : Entry()
        class Decided(val allow: Boolean, val atMs: Long) : Entry()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** How long an existing answer remains valid before requiring confirmation again. */
    private val memoryMs = 5 * 60_000L

    fun decide(peer: String): Boolean {
        val key = normalize(peer)

        while (true) {
            val existing = entries[key]

            if (existing is Entry.Decided) {
                if (System.currentTimeMillis() - existing.atMs < memoryMs) return existing.allow
                entries.remove(key, existing)
                continue
            }

            if (existing is Entry.Pending) {
                // Another copy of the same HELLO: waits for the pending response
                // instead of opening a second prompt on the same line.
                val answer = waitOn(existing.gate)
                return answer ?: false
            }

            val fresh = Entry.Pending()
            if (entries.putIfAbsent(key, fresh) != null) continue

            val allow = try {
                askNow(peer)
            } catch (e: Exception) {
                log("auth prompt failed: ${e.message}")
                false
            }

            entries[key] = Entry.Decided(allow, System.currentTimeMillis())
            // Wakes up any callers queued on this request: the decision is
            // already recorded above, so anyone who misses it here
            // will still find it in the map.
            fresh.gate.offer(allow)
            return allow
        }
    }

    private fun waitOn(gate: ArrayBlockingQueue<Boolean>): Boolean? {
        val wait = if (timeoutMs > 0) timeoutMs + 1000L else 300_000L
        return runCatching { gate.poll(wait, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun askNow(peer: String): Boolean {
        // In JSON mode, the terminal is a data stream for a program: there is
        // no one to prompt, and emitting a prompt would corrupt the output.
        if (jsonMode) {
            log("auth request from $peer denied: --json has no interactive prompt")
            return false
        }

        visualizer?.let { viz ->
            val gate = ArrayBlockingQueue<Boolean>(1)
            viz.askAuth(peer) { allow -> gate.offer(allow) }
            val answer =
                if (timeoutMs > 0) runCatching { gate.poll(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
                else runCatching { gate.take() }.getOrNull()
            if (answer == null) {
                viz.statusMsg = "!  $peer denied (no answer)"
                log("auth request from $peer timed out")
            }
            return answer ?: false
        }

        return ConsoleInput.askYesNo(
            "  Client $peer wants to connect. Allow? [y/N]",
            timeoutMs,
            default = false
        )
    }

    /** Clears recorded decisions: used when the server restarts or the key changes. */
    fun reset() = entries.clear()

    private fun normalize(peer: String): String = peer.trim().removePrefix("/")
}
