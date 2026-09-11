/*
 * Controlli sul parser SDP della ricezione RTP.
 *
 * Gli SDP di riferimento sono quelli che si incontrano davvero: quello emesso
 * da questa stessa app, quelli di ffmpeg, uno con payload type statico senza
 * rtpmap, uno con video prima dell'audio, e i casi degeneri. Il parser deve
 * essere tollerante: preferire un risultato con avvisi al rifiuto secco.
 */

private var rtpSdpFailures = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        println("  FAIL $name ${if (detail.isNotBlank()) "-> $detail" else ""}")
        rtpSdpFailures++
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "actual=$actual expected=$expected")

fun rtpSdpChecks() {
    println("== rtp sdp parser ==")

    // 1. Quello che emette WFAS stesso (multicast)
    val wfas = """
        v=0
        o=- 1788900000 1788900000 IN IP4 192.168.1.10
        s=WiFi Audio Streaming
        i=WFAS RTP stream -wfas.app
        c=IN IP4 239.255.0.1/4
        t=0 0
        a=tool:wfas
        m=audio 9094 RTP/AVP 96
        a=rtpmap:96 L16/48000/2
        a=ptime:10
        a=recvonly
    """.trimIndent()
    RtpSdp.parse(wfas).let { r ->
        check("wfas: ok", r.ok, r.error ?: "")
        eq("wfas: indirizzo", r.source?.address, "239.255.0.1")
        eq("wfas: porta", r.source?.port, 9094)
        eq("wfas: codec", r.source?.encoding, "L16")
        eq("wfas: rate", r.source?.sampleRate, 48000)
        eq("wfas: canali", r.source?.channels, 2)
        eq("wfas: pt", r.source?.payloadType, 96)
        eq("wfas: nome", r.source?.name, "WiFi Audio Streaming")
        check("wfas: multicast", r.source?.isMulticast == true)
        check("wfas: percorso nativo", r.source?.isNativePcm == true)
        check("wfas: nessun avviso", r.warnings.isEmpty(), r.warnings.toString())
    }

    // 2. ffmpeg -f rtp: unicast, TTL assente, riga i= assente
    val ff = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=No Name
        c=IN IP4 192.168.1.44
        t=0 0
        a=tool:libavformat 60.16.100
        m=audio 5004 RTP/AVP 97
        b=AS:128
        a=rtpmap:97 opus/48000/2
        a=fmtp:97 sprop-stereo=1
    """.trimIndent()
    RtpSdp.parse(ff).let { r ->
        check("ffmpeg/opus: ok", r.ok, r.error ?: "")
        eq("ffmpeg/opus: codec", r.source?.encoding, "opus")
        eq("ffmpeg/opus: porta", r.source?.port, 5004)
        check("ffmpeg/opus: non multicast", r.source?.isMulticast == false)
        check("ffmpeg/opus: va su ffmpeg", r.source?.isNativePcm == false)
    }

    // 3. Payload type statico, senza rtpmap (RFC 3551 PT 10 = L16/44100/2)
    RtpSdp.parse("v=0\no=- 0 0 IN IP4 10.0.0.1\ns=-\nc=IN IP4 10.0.0.1\nt=0 0\nm=audio 5555 RTP/AVP 10").let { r ->
        check("pt statico: ok", r.ok, r.error ?: "")
        eq("pt statico: codec", r.source?.encoding, "L16")
        eq("pt statico: rate", r.source?.sampleRate, 44100)
        eq("pt statico: canali", r.source?.channels, 2)
        eq("pt statico: nome vuoto da s=-", r.source?.name, "")
    }

    // 4. c= solo a livello di media, e video prima dell'audio
    val mixed = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=cam
        t=0 0
        m=video 5000 RTP/AVP 96
        a=rtpmap:96 H264/90000
        m=audio 5002 RTP/AVP 98
        c=IN IP4 239.1.2.3/16
        a=rtpmap:98 L16/44100/1
    """.trimIndent()
    RtpSdp.parse(mixed).let { r ->
        check("misto: ok", r.ok, r.error ?: "")
        eq("misto: prende l'audio", r.source?.port, 5002)
        eq("misto: c= del media", r.source?.address, "239.1.2.3")
        eq("misto: mono", r.source?.channels, 1)
        eq("misto: codec audio, non H264", r.source?.encoding, "L16")
        check("misto: avvisa delle altre tracce", "sdp_warn_multi_media" in r.warnings, r.warnings.toString())
    }

    // 5. Casi degeneri
    check("vuoto -> errore", RtpSdp.parse("").error == "sdp_err_empty")
    check("non-sdp -> errore", RtpSdp.parse("ciao\nmondo").error == "sdp_err_no_media")
    check("solo video -> nessun audio", RtpSdp.parse("v=0\ns=x\nt=0 0\nm=video 5000 RTP/AVP 96").error == "sdp_err_no_audio")
    check("porta 0 -> disattivata", RtpSdp.parse("v=0\ns=x\nc=IN IP4 1.2.3.4\nt=0 0\nm=audio 0 RTP/AVP 96").error == "sdp_err_port_zero")

    // 6. Senza c= e senza rtpmap: si usa comunque, con avvisi
    RtpSdp.parse("v=0\ns=x\nt=0 0\nm=audio 9000 RTP/AVP 96").let { r ->
        check("minimale: comunque usabile", r.ok, r.error ?: "")
        check("minimale: avvisa dell'indirizzo", "sdp_warn_no_connection" in r.warnings, r.warnings.toString())
        check("minimale: avvisa del codec", "sdp_warn_no_rtpmap" in r.warnings, r.warnings.toString())
        eq("minimale: default L16", r.source?.encoding, "L16")
    }

    // 7. CRLF e spazi: gli SDP incollati arrivano quasi sempre cosi'
    RtpSdp.parse("v=0\r\n o=- 0 0 IN IP4 1.1.1.1 \r\ns=x\r\nc=IN IP4 224.0.0.9\r\nt=0 0\r\nm=audio 7000 RTP/AVP 96\r\na=rtpmap:96 L16/48000/2\r\n").let { r ->
        check("crlf: ok", r.ok, r.error ?: "")
        eq("crlf: indirizzo", r.source?.address, "224.0.0.9")
        eq("crlf: porta", r.source?.port, 7000)
    }

    // 8. Andata e ritorno: sintetizza -> riparsa
    val manual = RtpSource(name = "Mixer", address = "239.9.9.9", port = 5004,
                           payloadType = 100, encoding = "L16", sampleRate = 44100, channels = 2)
    RtpSdp.parse(RtpSdp.synthesize(manual)).let { r ->
        check("roundtrip: ok", r.ok, r.error ?: "")
        eq("roundtrip: indirizzo", r.source?.address, manual.address)
        eq("roundtrip: porta", r.source?.port, manual.port)
        eq("roundtrip: pt", r.source?.payloadType, manual.payloadType)
        eq("roundtrip: rate", r.source?.sampleRate, manual.sampleRate)
        eq("roundtrip: canali", r.source?.channels, manual.channels)
    }

    // 9. Persistenza
    val saved = RtpSource(name = "Sala|prove", address = "239.0.0.5", port = 9094,
                          payloadType = 96, encoding = "L16", sampleRate = 48000, channels = 2)
    RtpSource.deserialize(saved.serialize()).let { d ->
        check("persistenza: rileggibile", d != null)
        eq("persistenza: la barra nel nome e' neutralizzata", d?.name, "Sala/prove")
        eq("persistenza: indirizzo", d?.address, saved.address)
        eq("persistenza: porta", d?.port, saved.port)
    }
    check("persistenza: riga corrotta -> null", RtpSource.deserialize("boh") == null)

    // 10. Validazione
    check("validazione: default ok", RtpSdp.validate(RtpSource()).isEmpty())
    check("validazione: porta fuori scala", "rtp_val_port" in RtpSdp.validate(RtpSource(port = 0)))
    check("validazione: 6 canali", "rtp_val_channels" in RtpSdp.validate(RtpSource(channels = 6)))
    check("validazione: indirizzo storto", "rtp_val_address" in RtpSdp.validate(RtpSource(address = "999.1.@@")))
    check("validazione: nome host ok", RtpSdp.validate(RtpSource(address = "studio.local")).isEmpty())
    check("multicast: 224-239", RtpSdp.isMulticastAddress("230.1.1.1") && !RtpSdp.isMulticastAddress("192.168.1.1"))
    check("multicast: ipv6 ff", RtpSdp.isMulticastAddress("ff02::1"))

    if (rtpSdpFailures > 0) {
        println("  $rtpSdpFailures controlli falliti")
        kotlin.system.exitProcess(1)
    }
}
