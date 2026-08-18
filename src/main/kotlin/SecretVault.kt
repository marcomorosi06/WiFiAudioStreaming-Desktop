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
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Custodia per i segreti, delegata all'OS.
 *
 * Su desktop non c'e' sandbox: il file di configurazione lo legge qualunque
 * programma giri col tuo utente, quindi la chiave precondivisa non puo' starci
 * in chiaro. Ogni piattaforma ha gia' il posto giusto dove metterla, e questo
 * oggetto si limita a scegliere quello disponibile.
 *
 * Va detto cosa NON protegge: un processo che gira come te puo' comunque
 * chiedere all'OS di decifrare, esattamente come root su Android. Serve contro
 * le copie a riposo — export del registro, backup, dotfile sincronizzati, dischi
 * portati altrove — e contro l'esposizione accidentale.
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
        "server_key_${serverId.replace(':', '_').replace('/', '_').replace('@', '_').replace(' ', '_')}"

    fun storeServerKey(serverId: String, key: String): Boolean =
        store(serverKeyAccount(serverId), key)

    fun loadServerKey(serverId: String): String? =
        load(serverKeyAccount(serverId))

    fun clearServerKey(serverId: String) {
        clear(serverKeyAccount(serverId))
    }

    private fun detect(): Backend? = when (ConfigPaths.os) {
        HostOs.WINDOWS -> DpapiBackend.takeIf { it.usable() }
        HostOs.MACOS   -> MacKeychainBackend.takeIf { it.usable() }
        HostOs.LINUX   -> SecretToolBackend.takeIf { it.usable() }
        HostOs.OTHER   -> null
    }

    // ── Windows ──────────────────────────────────────────────────────────────
    // DPAPI cifra con una chiave derivata dalle credenziali di login: il blob
    // non si apre su un'altra macchina ne' sotto un altro account. Lo teniamo in
    // un file accanto alla configurazione, cosi' il modello resta "per nome"
    // come sugli altri sistemi.
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

        private fun writeAll(entries: Map<String, String>) {
            val f = file
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            f.writeText(entries.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }

        override fun store(name: String, value: String): Boolean {
            val entries = readAll()
            entries[name] = protect(value)
            writeAll(entries)
            return true
        }

        override fun load(name: String): String? {
            val blob = readAll()[name] ?: return null
            // Un blob che non si apre viene da un altro account o da un'altra
            // macchina: e' rumore, non un errore da propagare.
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
            // -U aggiorna se esiste. Il valore passa come argomento: su macOS
            // 'ps' mostra gli argomenti solo allo stesso utente, che e' gia'
            // fuori dal modello di minaccia, ma resta la parte meno elegante.
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
    // secret-tool parla con il Secret Service (GNOME Keyring, KWallet). Su una
    // macchina headless non c'e', e li' il vault semplicemente non esiste: la
    // chiave verra' chiesta o passata da WFAS_AUTH_KEY.
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
            // Il segreto arriva da stdin, quindi non compare in 'ps'.
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
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        if (stdin != null) {
            proc.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        } else {
            runCatching { proc.outputStream.close() }
        }
        val out = proc.inputStream.bufferedReader().readText()
        runCatching { proc.errorStream.close() }
        val finished = proc.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out
    }
}
