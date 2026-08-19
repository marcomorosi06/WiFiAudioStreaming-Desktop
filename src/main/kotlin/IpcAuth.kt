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
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local control channel authentication.
 *
 * `wfas control ...` communicates with the already-running instance over a
 * loopback socket. Up to version 5.2, that socket answered anyone who managed
 * to connect: simply reading the port from a file in /tmp was enough to query
 * the status or terminate another user's stream on the machine. From now on,
 * two distinct proofs are required, and both are necessary:
 *
 *  1. **Session token.** Generated at startup, written to a file readable
 *     only by the owner inside a 0700 directory. This proves "I am a
 *     process belonging to your user": without read permissions on the file,
 *     the token cannot be retrieved, and without the token, the socket closes.
 *
 *  2. **Proof of key.** If the instance runs in KEY mode, the token alone
 *     is not sufficient: the server issues a nonce and the client must reply
 *     with an HMAC-SHA256 calculated over the pre-shared key and the exact command.
 *     The key is never sent over the wire, and binding the proof to the payload
 *     prevents reusing a valid response for a different command.
 *
 * The token protects against other users on the machine; the key protects
 * against cases where the file is read anyway (backups, root, shared home directory).
 * These are distinct security layers: neither makes the other redundant.
 */

object IpcAuth {

    const val PROTOCOL_VERSION = 1

    /** Separa il dominio: un HMAC di questo oggetto non e' valido altrove. */
    private const val PROOF_CONTEXT = "WFAS-IPC-v1"

    private const val SESSION_SUFFIX = ".port"
    private const val SESSION_PREFIX = "wfas-"

    private val rng = SecureRandom()

    // ── Primitive ────────────────────────────────────────────────────────────

    fun newToken(): String = randomHex(32)

    fun nonce(): String = randomHex(16)

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { rng.nextBytes(it) }.toHex()

    /**
     * The proof covers both the nonce and the payload: replaying the response
     * of a `status` command to execute a `stop` will not work, because the
     * payload is part of what gets signed.
     */
    fun proof(key: String, nonce: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$PROOF_CONTEXT:$nonce:$payload".toByteArray(Charsets.UTF_8)).toHex()
    }

    fun constantTimeEquals(a: String, b: String): Boolean = WfasAuth.constantTimeEquals(a, b)


    /**
     * Where session files live.
     *
     * On Linux, $XDG_RUNTIME_DIR is already a per-user 0700 directory on tmpfs,
     * cleaned up on logout: it is exactly the right place. Elsewhere, we fall back
     * to a user-owned subdirectory in the temp folder, created manually with 0700 permissions.
     * Under no circumstances do files remain in the shared temp directory, where
     * the name was predictable and permissions were left to default.
     */
    fun runtimeDir(): File {
        val xdg = System.getenv("XDG_RUNTIME_DIR")?.trim().orEmpty()
        val base = if (xdg.isNotEmpty() && File(xdg).isDirectory) {
            File(xdg, "wfas")
        } else {
            val user = System.getProperty("user.name")?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: "user"
            File(System.getProperty("java.io.tmpdir"), "wfas-$user")
        }
        if (!base.isDirectory) {
            runCatching { base.mkdirs() }
            lockDownDir(base)
        }
        return base
    }

    /** The legacy location, read only to detect outdated instances. */
    fun legacyDir(): File = File(System.getProperty("java.io.tmpdir"))

    fun sessionFile(pid: Long): File = File(runtimeDir(), "$SESSION_PREFIX$pid$SESSION_SUFFIX")

    data class Session(
        val pid: Long,
        val port: Int,
        val token: String?,
        val file: File,
        val legacy: Boolean
    ) {
        /** A file without a token originates from a build prior to authentication. */
        val authenticated: Boolean get() = !token.isNullOrEmpty()
    }

    fun writeSession(pid: Long, port: Int, token: String): File {
        val f = sessionFile(pid)
        // Permissions are set first, then data is written: doing the reverse
        // would leave a window, however narrow, in which the token is readable by everyone.
        runCatching {
            f.parentFile?.let { if (!it.isDirectory) it.mkdirs(); lockDownDir(it) }
            if (!f.exists()) f.createNewFile()
            lockDownFile(f)
            f.writeText(
                buildString {
                    append("# WiFi Audio Streaming control session - do not share\n")
                    append("v=$PROTOCOL_VERSION\n")
                    append("pid=$pid\n")
                    append("port=$port\n")
                    append("token=$token\n")
                }
            )
            lockDownFile(f)
        }
        return f
    }

    fun readSession(file: File, legacy: Boolean = false): Session? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val pid = file.name.removePrefix(SESSION_PREFIX).removeSuffix(SESSION_SUFFIX).toLongOrNull() ?: return null

        // The legacy format was just the port on a single line.
        val bare = text.trim().toIntOrNull()
        if (bare != null) return Session(pid, bare, null, file, legacy)

        val fields = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

        val port = fields["port"]?.toIntOrNull() ?: return null
        return Session(pid, port, fields["token"]?.takeIf { it.isNotEmpty() }, file, legacy)
    }

    /**
     * Active sessions, ordered from newest to oldest. Files belonging to dead
     * processes are removed here: otherwise, after a crash, the client would chase
     * a port that no longer responds and report "no instance found" even when an
     * older, perfectly active instance exists.
     */
    fun listSessions(): List<Session> {
        val found = ArrayList<Session>()

        fun scan(dir: File, legacy: Boolean) {
            val files = dir.listFiles { f: File ->
                f.isFile && f.name.startsWith(SESSION_PREFIX) && f.name.endsWith(SESSION_SUFFIX)
            } ?: return
            for (f in files.sortedByDescending { it.lastModified() }) {
                val s = readSession(f, legacy) ?: continue
                if (!isAlive(s.pid)) {
                    runCatching { f.delete() }
                    continue
                }
                if (legacy && !ownedByCurrentUser(f)) continue
                found += s
            }
        }

        scan(runtimeDir(), legacy = false)
        scan(legacyDir(), legacy = true)
        return found
    }

    fun isAlive(pid: Long): Boolean =
        runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(true)

    /**
     * File creator check. In a shared temp directory, another user could place
     * a `wfas-<pid>.port` file pointing to their own socket, causing the client
     * to hand over commands and proofs to them: without this check, the control
     * channel could be hijacked with a simple three-line file.
     */
    private fun ownedByCurrentUser(f: File): Boolean = runCatching {
        val owner = Files.getOwner(f.toPath())?.name ?: return@runCatching false
        val me = System.getProperty("user.name").orEmpty()
        owner == me || owner.substringAfterLast('\\') == me
    }.getOrDefault(false)

    private fun lockDownFile(f: File) {
        val posix = runCatching {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
            true
        }.getOrDefault(false)
        if (!posix) {
            // Windows: niente POSIX, ma togliere il bit "tutti" e rimetterlo solo
            // per il proprietario e' comunque un salto di qualita' rispetto al default.
            runCatching {
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            }
        }
    }

    private fun lockDownDir(d: File) {
        val posix = runCatching {
            Files.setPosixFilePermissions(
                d.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
            true
        }.getOrDefault(false)
        if (!posix) {
            runCatching {
                d.setReadable(false, false)
                d.setWritable(false, false)
                d.setExecutable(false, false)
                d.setReadable(true, true)
                d.setWritable(true, true)
                d.setExecutable(true, true)
            }
        }
    }

    private fun ByteArray.toHex(): String {
        val h = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (x in this) {
            val v = x.toInt() and 0xFF
            sb.append(h[v ushr 4]); sb.append(h[v and 15])
        }
        return sb.toString()
    }
}
