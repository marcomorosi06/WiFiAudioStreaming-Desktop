import kotlin.system.exitProcess

private var pairingFailures = 0

private fun ok(name: String, condition: Boolean, detail: String = "") {
    if (condition) println("  ok   $name")
    else {
        println("  FAIL $name ${if (detail.isNotBlank()) "-> $detail" else ""}")
        pairingFailures++
    }
}

private fun same(name: String, actual: Any?, expected: Any?) =
    ok(name, actual == expected, "actual=$actual expected=$expected")

private const val K = "N1e4Yx7Qp2Rk9Tz0AbCdEfGhIjKlMnOpQrStUvWxYz0"
private const val NOW = 1_800_000_000L

fun wfasPairingUriChecks() {
    println("== wfas pairing uri ==")

    val unicast = WfasPairingUri.build(
        ip = "192.168.1.10",
        port = 50005,
        mode = WfasPairingUri.MODE_UNICAST,
        keyBase64 = K,
        expEpochSeconds = NOW + 120
    )
    val p1 = WfasPairingUri.parse(unicast, NOW)
    ok("unicast parses", p1 != null)
    same("unicast ip", p1?.ip, "192.168.1.10")
    same("unicast port", p1?.port, 50005)
    same("unicast key", p1?.keyBase64, K)
    same("unicast has no epoch", p1?.mcastEpoch, null)
    same("unicast version", p1?.version, 2)

    val multicast = WfasPairingUri.build(
        ip = "239.255.0.1",
        port = 50005,
        mode = WfasPairingUri.MODE_MULTICAST,
        keyBase64 = K,
        expEpochSeconds = NOW + 120,
        mcastEpoch = 42L
    )
    val p2 = WfasPairingUri.parse(multicast, NOW)
    ok("multicast parses", p2 != null)
    ok("multicast flagged", p2?.isMulticast == true)
    same("multicast epoch", p2?.mcastEpoch, 42L)

    val withEpochOnUnicast = WfasPairingUri.build(
        ip = "192.168.1.10", port = 50005, mode = WfasPairingUri.MODE_UNICAST,
        keyBase64 = K, expEpochSeconds = NOW + 120, mcastEpoch = 7L
    )
    ok("unicast drops epoch", !withEpochOnUnicast.contains("epoch="))

    val v6 = WfasPairingUri.build(
        ip = "[fd00::1]", port = 50005, mode = WfasPairingUri.MODE_UNICAST,
        keyBase64 = K, expEpochSeconds = NOW + 120
    )
    same("ipv6 survives encoding", WfasPairingUri.parse(v6, NOW)?.ip, "[fd00::1]")

    val stale = WfasPairingUri.build(
        ip = "192.168.1.10", port = 50005, mode = WfasPairingUri.MODE_UNICAST,
        keyBase64 = K, expEpochSeconds = NOW - 120
    )
    ok("expired rejected", WfasPairingUri.parse(stale, NOW) == null)
    ok("expired recognised as expired", WfasPairingUri.isExpiredUri(stale, NOW))

    val skewed = WfasPairingUri.build(
        ip = "192.168.1.10", port = 50005, mode = WfasPairingUri.MODE_UNICAST,
        keyBase64 = K, expEpochSeconds = NOW - 10
    )
    ok("clock skew tolerated", WfasPairingUri.parse(skewed, NOW) != null)

    val badVersion = "wifiaudio://pair?ip=192.168.1.10&port=50005&mode=unicast&key=$K&exp=${NOW + 120}&v=99"
    ok("unsupported version rejected", WfasPairingUri.parse(badVersion, NOW) == null)
    ok("bad version is not 'expired'", !WfasPairingUri.isExpiredUri(badVersion, NOW))

    ok(
        "wrong scheme rejected",
        WfasPairingUri.parse("wifiaudio2://pair?ip=1.2.3.4&port=1&mode=unicast&key=$K&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "wrong host rejected",
        WfasPairingUri.parse("wifiaudio://connect?ip=1.2.3.4&port=1&mode=unicast&key=$K&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "missing key rejected",
        WfasPairingUri.parse("wifiaudio://pair?ip=1.2.3.4&port=1&mode=unicast&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "missing ip rejected",
        WfasPairingUri.parse("wifiaudio://pair?port=1&mode=unicast&key=$K&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "missing exp rejected",
        WfasPairingUri.parse("wifiaudio://pair?ip=1.2.3.4&port=1&mode=unicast&key=$K&v=2", NOW) == null
    )
    ok(
        "unknown mode rejected",
        WfasPairingUri.parse("wifiaudio://pair?ip=1.2.3.4&port=1&mode=broadcast&key=$K&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "out of range port rejected",
        WfasPairingUri.parse("wifiaudio://pair?ip=1.2.3.4&port=70000&mode=unicast&key=$K&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "illegal key charset rejected",
        WfasPairingUri.parse("wifiaudio://pair?ip=1.2.3.4&port=1&mode=unicast&key=short%2Fkey&exp=${NOW + 9}&v=2", NOW) == null
    )
    ok(
        "garbage rejected",
        WfasPairingUri.parse("not a uri at all", NOW) == null &&
                WfasPairingUri.parse("", NOW) == null &&
                WfasPairingUri.parse("wifiaudio://pair", NOW) == null
    )

    val appLink = WfasPairingUri.buildAppLink(
        ip = "192.168.1.10", port = 50005, mode = WfasPairingUri.MODE_UNICAST,
        keyBase64 = K, expEpochSeconds = NOW + 120
    )
    same("https app link parses", WfasPairingUri.parse(appLink, NOW)?.ip, "192.168.1.10")
    ok("app link puts the fields in the fragment", appLink.contains('#') && !appLink.substringBefore('#').contains('?'))
    run {
        val fields = "ip=192.168.1.10&port=50005&mode=unicast&key=$K&exp=${NOW + 120}&v=2"
        ok(
            "apex host accepted like www",
            WfasPairingUri.parse("https://marcomorosi.eu${WfasPairingUri.APPLINK_PATH}#$fields", NOW) != null
        )
        ok(
            "trailing slash accepted",
            WfasPairingUri.parse("https://www.marcomorosi.eu${WfasPairingUri.APPLINK_PATH}/#$fields", NOW) != null
        )
    }
    ok("app link path carries no secret", !appLink.substringBefore('#').contains(K))
    ok(
        "legacy query-string app link still parses",
        WfasPairingUri.parse(
            "https://${WfasPairingUri.APPLINK_HOST}${WfasPairingUri.APPLINK_PATH}" +
                "?ip=192.168.1.10&port=50005&mode=unicast&key=$K&exp=${NOW + 120}&v=2",
            NOW
        )?.port == 50005
    )
    same(
        "italian app link fragment parses",
        WfasPairingUri.parse(
            WfasPairingUri.buildAppLink(
                ip = "239.255.0.1", port = 50005, mode = WfasPairingUri.MODE_MULTICAST,
                keyBase64 = K, expEpochSeconds = NOW + 120, mcastEpoch = 7L, italian = true
            ),
            NOW
        )?.mcastEpoch,
        7L
    )
    ok(
        "foreign https host rejected",
        WfasPairingUri.parse(
            "https://evil.example/wifi-audio-streaming/pair?ip=1.2.3.4&port=1&mode=unicast&key=$K&exp=${NOW + 9}&v=2",
            NOW
        ) == null
    )

    val generated = WfasAuth.randomPairingKey()
    same("generated key length", generated.length, 43)
    ok("generated key is url-safe base64", Regex("^[A-Za-z0-9_-]+$").matches(generated))
    ok("generated keys differ", generated != WfasAuth.randomPairingKey())

    same("qr ui mode maps to KEY", SecurityMode.storedMode("QR"), "KEY")
    same("KEY + flag reads as QR", SecurityMode.uiMode("KEY", true), "QR")
    same("KEY without flag stays KEY", SecurityMode.uiMode("KEY", false), "KEY")
    same("OFF ignores flag", SecurityMode.uiMode("OFF", true), "OFF")
    ok("QR requires a key", SecurityMode.requiresKey(SecurityMode.storedMode("QR")))

    println()
    if (pairingFailures == 0) println("ALL PAIRING CHECKS PASSED")
    else println("$pairingFailures PAIRING CHECK(S) FAILED")
    if (pairingFailures > 0) exitProcess(1)
}
