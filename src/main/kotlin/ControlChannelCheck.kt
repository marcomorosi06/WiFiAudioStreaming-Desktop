/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

import kotlin.system.exitProcess

private var controlFailures = 0

private fun cok(name: String, condition: Boolean, detail: String = "") {
    if (condition) println("  ok   $name")
    else {
        println("  FAIL $name ${if (detail.isNotBlank()) "-> $detail" else ""}")
        controlFailures++
    }
}

private fun csame(name: String, actual: Any?, expected: Any?) =
    cok(name, actual == expected, "actual=$actual expected=$expected")

fun controlChannelChecks() {
    println("== ipc auth ==")

    val key = "s3cr3t-key"
    val nonce = IpcAuth.nonce()
    val payload = "{\"cmd\": \"status\"}"

    val good = IpcAuth.proof(key, nonce, payload)
    cok("proof is deterministic", good == IpcAuth.proof(key, nonce, payload))
    cok("proof is hex sha256", Regex("^[0-9a-f]{64}$").matches(good))

    // Le tre proprieta' che rendono il canale non aggirabile: chiave sbagliata,
    // nonce riusato e comando diverso devono produrre prove diverse.
    cok("wrong key gives a different proof", IpcAuth.proof("other", nonce, payload) != good)
    cok("different nonce gives a different proof", IpcAuth.proof(key, IpcAuth.nonce(), payload) != good)
    cok(
        "proof is bound to the command",
        IpcAuth.proof(key, nonce, "{\"cmd\": \"stop\"}") != good
    )

    cok("tokens are 256-bit hex", Regex("^[0-9a-f]{64}$").matches(IpcAuth.newToken()))
    cok("tokens differ", IpcAuth.newToken() != IpcAuth.newToken())
    cok("token comparison is exact", !IpcAuth.constantTimeEquals(IpcAuth.newToken(), IpcAuth.newToken()))

    val t = IpcAuth.newToken()
    cok("same token compares equal", IpcAuth.constantTimeEquals(t, t))

    // Formato del file di sessione: la porta si legge sia dal formato nuovo sia
    // da quello vecchio a una riga, cosi' un'istanza datata viene riconosciuta
    // come tale invece di sembrare assente.
    val dir = kotlin.io.path.createTempDirectory("wfas-session").toFile()
    val modern = java.io.File(dir, "wfas-4242.port")
    modern.writeText("# header\nv=1\npid=4242\nport=51000\ntoken=$t\n")
    val parsedModern = IpcAuth.readSession(modern)
    csame("modern session pid", parsedModern?.pid, 4242L)
    csame("modern session port", parsedModern?.port, 51000)
    csame("modern session token", parsedModern?.token, t)
    cok("modern session is authenticated", parsedModern?.authenticated == true)

    val legacy = java.io.File(dir, "wfas-77.port")
    legacy.writeText("50999\n")
    val parsedLegacy = IpcAuth.readSession(legacy, legacy = true)
    csame("legacy session port", parsedLegacy?.port, 50999)
    csame("legacy session has no token", parsedLegacy?.token, null)
    cok("legacy session is not authenticated", parsedLegacy?.authenticated == false)
    dir.deleteRecursively()

    println()
    println("== auto-connect targets ==")

    csame("bare ip still parses", AutoConnectTarget.parse("192.168.1.50")?.ip, "192.168.1.50")
    csame("bare ip has no port", AutoConnectTarget.parse("192.168.1.50")?.port, null)
    cok("bare ip is enabled", AutoConnectTarget.parse("192.168.1.50")?.enabled == true)
    csame("blank line is dropped", AutoConnectTarget.parse("   "), null)

    val full = AutoConnectTarget("10.0.0.5", 9191, "Salotto", true, "ref-1")
    val round = AutoConnectTarget.parse(full.serialize())
    csame("round-trip ip", round?.ip, full.ip)
    csame("round-trip port", round?.port, full.port)
    csame("round-trip label", round?.label, full.label)
    csame("round-trip keyRef", round?.keyRef, full.keyRef)
    csame("round-trip enabled", round?.enabled, true)

    val disabled = full.copy(enabled = false)
    csame("disabled round-trips", AutoConnectTarget.parse(disabled.serialize())?.enabled, false)

    // L'etichetta e' testo libero: il separatore al suo interno non deve poter
    // spezzare la voce in due campi.
    val tricky = AutoConnectTarget("10.0.0.6", null, "Studio|Ufficio", true, "")
    val trickyBack = AutoConnectTarget.parse(tricky.serialize())
    csame("label with separator survives", trickyBack?.label, "Studio|Ufficio")
    csame("label with separator keeps ip", trickyBack?.ip, "10.0.0.6")

    csame("out of range port is ignored", AutoConnectTarget.parse("10.0.0.7|port=70000")?.port, null)
    csame("non numeric port is ignored", AutoConnectTarget.parse("10.0.0.8|port=abc")?.port, null)

    val list = listOf(full, disabled.copy(ip = "10.0.0.9"))
    csame("list round-trip size", AutoConnectTarget.parseList(AutoConnectTarget.serializeList(list)).size, 2)
    csame("display name falls back to ip", AutoConnectTarget("10.0.0.10").displayName(), "10.0.0.10")
    csame("display name prefers label", full.displayName(), "Salotto")

    println()
    println("== auto-start security ==")

    csame("inherit follows the general mode", AutoStartSecurity.resolve("INHERIT", "KEY"), "KEY")
    csame("blank behaves as inherit", AutoStartSecurity.resolve("", "ASK"), "ASK")
    csame("explicit mode wins", AutoStartSecurity.resolve("KEY", "OFF"), "KEY")
    csame("unknown value falls back safely", AutoStartSecurity.resolve("nonsense", "OFF"), "OFF")

    // La cifratura poggia sulla chiave: senza chiave non deve poter risultare
    // attiva in nessun modo, nemmeno chiedendola esplicitamente.
    cok(
        "encryption off without a key mode",
        !AutoStartSecurity.resolveEncryption("ON", true, "OFF")
    )
    cok(
        "encryption off in ask mode",
        !AutoStartSecurity.resolveEncryption("ON", true, "ASK")
    )
    cok(
        "explicit on wins over the general setting",
        AutoStartSecurity.resolveEncryption("ON", false, "KEY")
    )
    cok(
        "explicit off wins over the general setting",
        !AutoStartSecurity.resolveEncryption("OFF", true, "KEY")
    )
    cok(
        "inherit follows the general setting when on",
        AutoStartSecurity.resolveEncryption("INHERIT", true, "KEY")
    )
    cok(
        "inherit follows the general setting when off",
        !AutoStartSecurity.resolveEncryption("INHERIT", false, "KEY")
    )
    cok(
        "blank behaves as inherit",
        AutoStartSecurity.resolveEncryption("", true, "KEY")
    )

    println()
    if (controlFailures == 0) println("ALL CONTROL CHECKS PASSED")
    else println("$controlFailures CONTROL CHECK(S) FAILED")
    if (controlFailures > 0) exitProcess(1)
}