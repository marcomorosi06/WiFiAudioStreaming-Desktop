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

/**
 * A server in the auto-connect list.
 *
 * Previously, this was a plain string: just the address. That was sufficient
 * as long as auto-connect was only used to reach an open server on the default
 * port, but it failed against a server in KEY mode — prompting for the key
 * required a GUI dialog, and a dialog makes no sense in an unattended procedure.
 * Here, each entry carries everything needed to connect without any user
 * interaction: port, label, and the reference to the key in the system keychain.
 *
 * The key is **not** stored in this structure and does not end up in the
 * configuration file: only the lookup name used to retrieve it from the
 * [SecretVault] is kept here, exactly as with the primary key.
 */
data class AutoConnectTarget(
    val ip: String,
    val port: Int? = null,
    val label: String = "",
    val enabled: Boolean = true,
    val keyRef: String = ""
) {
    val hasKey: Boolean get() = keyRef.isNotBlank()

    fun displayName(): String = label.ifBlank { ip }

    fun resolveKey(): String? =
        if (!hasKey) null else SecretVault.load(vaultName(keyRef))?.takeIf { it.isNotBlank() }

    fun serialize(): String = buildString {
        append(ip.trim())
        port?.let { append("|port=").append(it) }
        if (label.isNotBlank()) append("|label=").append(escape(label))
        if (keyRef.isNotBlank()) append("|key=").append(escape(keyRef))
        if (!enabled) append("|off")
    }

    companion object {
        const val VAULT_PREFIX = "autoConnect."

        fun vaultName(keyRef: String): String = "$VAULT_PREFIX$keyRef"

        fun newKeyRef(): String =
            java.lang.Long.toHexString(System.currentTimeMillis()) + "-" +
                java.lang.Integer.toHexString((0..0xFFFF).random())

        fun parse(raw: String): AutoConnectTarget? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val parts = text.split("|")
            val ip = parts[0].trim()
            if (ip.isEmpty()) return null

            var port: Int? = null
            var label = ""
            var keyRef = ""
            var enabled = true

            for (opt in parts.drop(1)) {
                val o = opt.trim()
                when {
                    o.equals("off", true)      -> enabled = false
                    o.equals("on", true)       -> enabled = true
                    o.startsWith("port=", true)  -> port = o.substringAfter('=').toIntOrNull()
                    o.startsWith("label=", true) -> label = unescape(o.substringAfter('='))
                    o.startsWith("key=", true)   -> keyRef = unescape(o.substringAfter('='))
                }
            }
            return AutoConnectTarget(ip, port?.takeIf { it in 1..65535 }, label, enabled, keyRef)
        }

        fun parseList(raw: List<String>): List<AutoConnectTarget> = raw.mapNotNull { parse(it) }

        fun serializeList(list: List<AutoConnectTarget>): List<String> = list.map { it.serialize() }

        /**
         * Saves the key in the vault and returns the updated entry.
         * An empty key clears the existing one: this provides a way
         * to remove the key from an entry without having to recreate it.
         */
        fun withKey(target: AutoConnectTarget, key: String): AutoConnectTarget {
            if (key.isBlank()) {
                if (target.hasKey) SecretVault.clear(vaultName(target.keyRef))
                return target.copy(keyRef = "")
            }
            val ref = target.keyRef.ifBlank { newKeyRef() }
            val stored = SecretVault.store(vaultName(ref), key)
            if (!stored) {
                // No vault available: do not fall back to the configuration
                // file, as that would mean writing in plain text a
                // key that the user expects to be protected.
                AppDebug.log("[AUTOCONNECT] no credential store available, key for ${target.ip} not saved")
                return target.copy(keyRef = "")
            }
            return target.copy(keyRef = ref)
        }

        fun forget(target: AutoConnectTarget) {
            if (target.hasKey) SecretVault.clear(vaultName(target.keyRef))
        }

        private fun escape(s: String): String =
            s.replace("%", "%25").replace("|", "%7C").replace("\n", " ")

        private fun unescape(s: String): String =
            s.replace("%7C", "|").replace("%7c", "|").replace("%25", "%")
    }
}
