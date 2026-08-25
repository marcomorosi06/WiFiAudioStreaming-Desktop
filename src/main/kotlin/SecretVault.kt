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

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Secret storage, delegated to the OS.
 *
 * There is no sandbox on the desktop: the configuration file is readable by
 * anything running as your user, so the pre-shared key cannot sit in it in
 * cleartext. Every platform already has the right place to keep it, and this
 * object does no more than pick whichever one is available.
 *
 * Worth stating what this does not protect: a process running as you can still
 * ask the OS to decrypt, exactly as root can on Android. It is there for copies
 * at rest - registry exports, backups, synced dotfiles, disks read elsewhere -
 * and for accidental exposure.
 */
object SecretVault {

    const val SERVICE = "WiFiAudioStreaming"

    interface Backend {
        val label: String
        fun store(name: String, value: String): Boolean
        fun load(name: String): String?
        fun clear(name: String)
    }

    val backend: Backend? by lazy { detect() }

    val available: Boolean get() = backend != null

    val label: String get() = backend?.label ?: "none"

    fun store(name: String, value: String): Boolean =
        backend?.let { runCatching { it.store(name, value) }.getOrDefault(false) } ?: false

    fun load(name: String): String? =
        backend?.let { runCatching { it.load(name) }.getOrNull() }

    fun clear(name: String) {
        backend?.let { runCatching { it.clear(name) } }
    }

    fun serverKeyAccount(serverId: String): String =
        "server_key_" + serverId.trim().lowercase().replace(':', '_').replace('.', '_').replace('-', '_')

    fun storeServerKey(serverId: String, key: String): Boolean {
        if (serverId.isBlank()) return false
        return store(serverKeyAccount(serverId), key)
    }

    fun loadServerKey(serverId: String): String? {
        if (serverId.isBlank()) return null
        return load(serverKeyAccount(serverId))
    }

    fun clearServerKey(serverId: String) {
        if (serverId.isBlank()) return
        clear(serverKeyAccount(serverId))
    }

    /**
     * Health of the credential store, distinguishing "there is nothing to talk to"
     * from "there is, but it does not work". [NoStore] is decided cheaply from the
     * detected backend; [Failing] comes from actually writing, reading back, and
     * clearing a probe value, which catches a locked keyring, a broken DPAPI, or a
     * Secret Service that answers but refuses to store. The result is cached: the
     * probe touches the real store (and may prompt to unlock it) exactly once.
     */
    sealed class Health {
        object Ok : Health()
        /** No usable backend at all. */
        object NoStore : Health()
        /** A backend exists but a store/read-back probe failed. */
        data class Failing(val detail: String) : Health()
    }

    @Volatile private var cachedHealth: Health? = null

    fun health(force: Boolean = false): Health {
        if (!force) cachedHealth?.let { return it }
        val b = backend ?: return Health.NoStore.also { cachedHealth = it }
        val probe = "wfas-selftest"
        val value = "ok-" + java.lang.Long.toHexString(System.nanoTime())
        val result = runCatching {
            if (!b.store(probe, value)) return@runCatching Health.Failing("write was rejected")
            val readBack = b.load(probe)
            runCatching { b.clear(probe) }
            if (readBack == value) Health.Ok else Health.Failing("value could not be read back")
        }.getOrElse { Health.Failing(it.message ?: it.javaClass.simpleName) }
        cachedHealth = result
        return result
    }

    private fun detect(): Backend? = when (ConfigPaths.os) {
        HostOs.WINDOWS -> DpapiBackend.takeIf { it.usable() }
        HostOs.MACOS   -> MacKeychainBackend.takeIf { it.usable() }
        HostOs.LINUX   -> SecretToolBackend.takeIf { it.usable() }
        HostOs.OTHER   -> null
    }

    // ── Windows ──────────────────────────────────────────────────────────────
    // DPAPI encrypts with a key derived from the login credentials, so the blob
    // does not open on another machine or under another account. We keep it in a
    // file next to the configuration, which keeps the "by name" model the other
    // platforms use.
    private object DpapiBackend : Backend {
        override val label = "Windows DPAPI"

        private val file: File get() = File(ConfigPaths.configDir(), "secrets.dat")

        fun usable(): Boolean = runCatching {
            Class.forName("com.sun.jna.platform.win32.Crypt32Util")
            true
        }.getOrDefault(false)

        private fun protect(value: String): String =
            Base64.getEncoder().encodeToString(
                com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))
            )

        private fun unprotect(blob: String): String =
            String(
                com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(blob)),
                Charsets.UTF_8
            )

        private fun readAll(): MutableMap<String, String> {
            val f = file
            if (!f.isFile) return mutableMapOf()
            val out = mutableMapOf<String, String>()
            f.readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
            return out
        }

        // Written through a temporary file and moved into place. A direct write
        // that dies halfway leaves the store truncated, and everything in it is
        // gone: this file holds every secret, not one. Permissions are narrowed
        // while the temporary file is still empty, so no content is ever briefly
        // readable at the default.
        private fun writeAll(entries: Map<String, String>) {
            val f = file
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val text = entries.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
            val tmp = File(f.parentFile ?: File("."), f.name + ".tmp")
            runCatching { if (!tmp.exists()) tmp.createNewFile() }
            ownerOnly(tmp)
            tmp.writeText(text)
            val moved = runCatching {
                Files.move(
                    tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
                true
            }.getOrElse {
                runCatching {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    true
                }.getOrDefault(false)
            }
            if (!moved) {
                f.writeText(text)
                runCatching { tmp.delete() }
            }
            ownerOnly(f)
        }

        // DPAPI already ties the blob to this account, so this is defence in depth
        // rather than the control that matters. Windows has no POSIX bits: clearing
        // "everyone" and granting the owner back is what there is.
        private fun ownerOnly(f: File) {
            runCatching {
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            }
        }

        override fun store(name: String, value: String): Boolean {
            val entries = readAll()
            entries[name] = protect(value)
            writeAll(entries)
            return true
        }

        override fun load(name: String): String? {
            val blob = readAll()[name] ?: return null
            // A blob that will not open came from another account or another
            // machine: that is noise, not an error worth propagating.
            return runCatching { unprotect(blob) }.getOrNull()
        }

        override fun clear(name: String) {
            val entries = readAll()
            if (entries.remove(name) != null) writeAll(entries)
        }
    }

    // ── macOS ────────────────────────────────────────────────────────────────
    private object MacKeychainBackend : Backend {
        override val label = "macOS Keychain"

        fun usable(): Boolean = File("/usr/bin/security").canExecute()

        override fun store(name: String, value: String): Boolean {
            // -U updates in place if the entry exists. The value goes on the
            // argv, which is the least elegant part of this backend: it is only
            // visible to the same user, already outside the threat model, but
            // stdin would still be preferable if the tool took it here.
            val code = exec(
                listOf(
                    "/usr/bin/security", "add-generic-password",
                    "-U", "-s", SERVICE, "-a", name, "-w", value
                )
            ).first
            return code == 0
        }

        override fun load(name: String): String? {
            val (code, out) = exec(
                listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", name, "-w")
            )
            return if (code == 0) out.trim().ifEmpty { null } else null
        }

        override fun clear(name: String) {
            exec(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", name))
        }
    }

    // ── Linux ────────────────────────────────────────────────────────────────
    // secret-tool talks to the Secret Service (GNOME Keyring, KWallet). On a
    // headless machine there is none, and there the vault simply does not exist:
    // the key is prompted for, or supplied through WFAS_AUTH_KEY.
    private object SecretToolBackend : Backend {
        override val label = "libsecret (secret-tool)"

        private val binary: String? by lazy {
            listOf("/usr/bin/secret-tool", "/bin/secret-tool", "/usr/local/bin/secret-tool")
                .firstOrNull { File(it).canExecute() }
        }

        fun usable(): Boolean =
            binary != null && !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()

        override fun store(name: String, value: String): Boolean {
            val bin = binary ?: return false
            // The secret arrives on stdin, so it never reaches the argv.
            val code = exec(
                listOf(bin, "store", "--label=$SERVICE", "service", SERVICE, "account", name),
                stdin = value
            ).first
            return code == 0
        }

        override fun load(name: String): String? {
            val bin = binary ?: return null
            val (code, out) = exec(listOf(bin, "lookup", "service", SERVICE, "account", name))
            return if (code == 0) out.trimEnd('\n').ifEmpty { null } else null
        }

        override fun clear(name: String) {
            val bin = binary ?: return
            exec(listOf(bin, "clear", "service", SERVICE, "account", name))
        }
    }

    private fun exec(cmd: List<String>, stdin: String? = null): Pair<Int, String> {
        // stderr is discarded by the OS rather than left on a pipe nobody reads: a
        // child that fills an unread pipe blocks there until the timeout kills it,
        // and closing the read end instead would hand it an error mid-write.
        val proc = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (stdin != null) {
            proc.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        } else {
            runCatching { proc.outputStream.close() }
        }
        val out = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out
    }
}
