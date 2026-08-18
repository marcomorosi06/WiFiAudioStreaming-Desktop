/*
 * Test suite for new features:
 * - Multi-language support (vi, en, it, auto)
 * - SecretVault secure key storage & auto-forget behavior
 * - SavedServerItem serialization and parsing
 * - AppSettings defaults & config integration
 */

import kotlin.system.exitProcess

object NewFeaturesCheck {
    private var checkFailures = 0

    private fun ok(name: String, condition: Boolean, detail: String = "") {
        if (condition) println("  [OK]   $name")
        else {
            println("  [FAIL] $name ${if (detail.isNotBlank()) "-> $detail" else ""}")
            checkFailures++
        }
    }

    private fun same(name: String, actual: Any?, expected: Any?) =
        ok(name, actual == expected, "actual='$actual' expected='$expected'")

    fun runAll() {
        println("==================================================")
        println("Running New Features Verification Checks")
        println("==================================================")

        testLanguageSupport()
        testSavedServerItemSerialization()
        testSecretVaultAndAutoForget()

        println("==================================================")
        if (checkFailures == 0) {
            println("ALL NEW FEATURE CHECKS PASSED (0 failures)")
            println("==================================================")
        } else {
            println("FAILED: $checkFailures check(s) failed!")
            println("==================================================")
            exitProcess(1)
        }
    }

    private fun testLanguageSupport() {
        println("\n-- Testing Language Support (Strings.setLanguage) --")

        // 1. English
        Strings.setLanguage("en")
        same("Language set to English", Strings.currentLanguage, "en")
        same("English saved_servers_title", Strings.get("saved_servers_title"), "Saved Server Passwords")
        same("English key_dialog_remember", Strings.get("key_dialog_remember"), "Remember password for this server")
        same("English key_dialog_wrong_saved", Strings.get("key_dialog_wrong_saved"), "Saved password for this server was incorrect or has changed. Please enter the new password:")
        same("English auto_reconnect_title", Strings.get("auto_reconnect_title"), "Auto-reconnect")
        same("English language", Strings.get("language"), "Language")

        // 2. Vietnamese
        Strings.setLanguage("vi")
        same("Language set to Vietnamese", Strings.currentLanguage, "vi")
        same("Vietnamese saved_servers_title", Strings.get("saved_servers_title"), "Mật khẩu máy chủ đã lưu")
        same("Vietnamese key_dialog_remember", Strings.get("key_dialog_remember"), "Ghi nhớ mật khẩu cho máy chủ này")
        same("Vietnamese key_dialog_wrong_saved", Strings.get("key_dialog_wrong_saved"), "Mật khẩu đã lưu không chính xác hoặc đã bị đổi trên máy chủ. Vui lòng nhập lại:")
        same("Vietnamese auto_reconnect_title", Strings.get("auto_reconnect_title"), "Tự động kết nối lại")
        same("Vietnamese language", Strings.get("language"), "Ngôn ngữ")
        same("Vietnamese language_auto", Strings.get("language_auto"), "Tự động (Theo hệ thống)")

        // 3. Italian
        Strings.setLanguage("it")
        same("Language set to Italian", Strings.currentLanguage, "it")
        same("Italian saved_servers_title", Strings.get("saved_servers_title"), "Password server salvate")
        same("Italian key_dialog_remember", Strings.get("key_dialog_remember"), "Ricorda la password per questo server")
        same("Italian key_dialog_wrong_saved", Strings.get("key_dialog_wrong_saved"), "La password salvata per questo server non è corretta o è cambiata. Inserisci la nuova password:")
        same("Italian auto_reconnect_title", Strings.get("auto_reconnect_title"), "Riconnessione automatica")

        // 4. Auto mode
        Strings.setLanguage("auto")
        same("Language set to Auto", Strings.currentLanguage, "auto")
        ok("Auto loaded non-empty saved_servers_title", Strings.get("saved_servers_title").isNotBlank())
    }

    private fun testSavedServerItemSerialization() {
        println("\n-- Testing SavedServerItem Serialization & Parsing --")

        val item1 = SavedServerItem(
            id = "192.168.1.100",
            name = "Living Room PC",
            ip = "192.168.1.100",
            port = "9090",
            lastConnected = 1700000000000L
        )

        val serialized = item1.toSerialized()
        ok("Serialized is not blank", serialized.isNotBlank())

        val parsed = SavedServerItem.fromSerialized(serialized)
        ok("Parsed is not null", parsed != null)
        same("Parsed id matches", parsed?.id, item1.id)
        same("Parsed name matches", parsed?.name, item1.name)
        same("Parsed ip matches", parsed?.ip, item1.ip)
        same("Parsed port matches", parsed?.port, item1.port)
        same("Parsed lastConnected matches", parsed?.lastConnected, item1.lastConnected)

        // Parsing format with pipe delimiters: id|name|ip|port|lastConnected
        val rawItem = "srv1|Office Audio|10.0.0.5|9090|12345"
        val parsedRaw = SavedServerItem.fromSerialized(rawItem)
        ok("Parsed raw pipe item is not null", parsedRaw != null)
        same("Parsed raw name", parsedRaw?.name, "Office Audio")
        same("Parsed raw ip", parsedRaw?.ip, "10.0.0.5")

        // Empty string parsing
        same("Empty string parses to null", SavedServerItem.fromSerialized(""), null)
    }

    private fun testSecretVaultAndAutoForget() {
        println("\n-- Testing SecretVault Storage & Auto-Forget Simulation --")

        val testIp = "127.0.0.99"
        val testPassword = "SuperSecretPassword123"

        // 1. Account name formatting
        val accountName = SecretVault.serverKeyAccount(testIp)
        same("Server key account name", accountName, "server_key_127.0.0.99")

        // 2. Store and Load
        if (SecretVault.available) {
            println("  (SecretVault native backend is available on this platform)")
            val stored = SecretVault.storeServerKey(testIp, testPassword)
            ok("storeServerKey succeeded", stored)

            val loaded = SecretVault.loadServerKey(testIp)
            same("loadServerKey returns stored password", loaded, testPassword)

            // 3. Simulate Auto-Forget on Authentication Failure
            println("  Simulating Auto-Forget on Auth Failure...")
            SecretVault.clearServerKey(testIp)

            val afterForget = SecretVault.loadServerKey(testIp)
            same("Password is forgotten (null)", afterForget, null)
        } else {
            println("  (SecretVault native backend not active in this test runner environment)")
            ok("SecretVault account formatting helper works", accountName == "server_key_127.0.0.99")
        }
    }
}

fun main() {
    NewFeaturesCheck.runAll()
}
