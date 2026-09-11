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
 *
 * --------------------------------------------------------------------------
 * RtpSdp.kt
 *
 * Modello e parser SDP per la RICEZIONE di flussi RTP.
 *
 * Il parser e' deliberatamente tollerante: gli SDP che girano davvero (ffmpeg,
 * VLC, mixer hardware, GStreamer) sono spesso incompleti o fuori ordine. Invece
 * di rifiutare, si estrae quel che c'e' e si segnalano i buchi come avvisi, in
 * modo che la UI possa mostrarli e lasciare all'utente il completamento a mano.
 *
 * Nessuna dipendenza dal protocollo WFAS: questo e' un percorso separato.
 */

/** Come e' stata ottenuta la configurazione: serve alla UI per raccontarlo. */
enum class RtpSourceOrigin { SDP_FILE, SDP_PASTE, MANUAL, SAVED }

/**
 * Una sorgente RTP che l'app puo' ascoltare.
 *
 * [address] e' il gruppo multicast oppure l'indirizzo su cui restare in ascolto.
 * Per l'unicast il valore utile e' la porta: si ascolta su tutte le interfacce,
 * quindi un indirizzo vuoto o 0.0.0.0 e' legittimo.
 */
data class RtpSource(
    val name: String = "",
    val address: String = "",
    val port: Int = 9094,
    val payloadType: Int = 96,
    val encoding: String = "L16",
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val origin: RtpSourceOrigin = RtpSourceOrigin.MANUAL,
    /** SDP originale, se c'era: serve al percorso FFmpeg per i codec non nativi. */
    val sdpText: String? = null
) {
    val isMulticast: Boolean get() = RtpSdp.isMulticastAddress(address)

    /** Il percorso nativo copre solo PCM lineare big endian a 16 bit. */
    val isNativePcm: Boolean
        get() = encoding.equals("L16", ignoreCase = true) && channels in 1..2

    fun displayName(): String =
        name.ifBlank { if (address.isBlank()) "RTP :$port" else "$address:$port" }

    fun formatSummary(): String =
        "$encoding $sampleRate Hz · ${if (channels == 1) "mono" else "$channels ch"} · PT $payloadType"

    /**
     * Riga di testo compatta per la persistenza. Il separatore verticale non
     * compare in indirizzi o nomi di codec; nel nome viene neutralizzato.
     */
    fun serialize(): String = listOf(
        name.replace('|', '/'), address, port.toString(), payloadType.toString(),
        encoding, sampleRate.toString(), channels.toString()
    ).joinToString("|")

    companion object {
        fun deserialize(line: String): RtpSource? {
            val p = line.split('|')
            if (p.size < 7) return null
            return RtpSource(
                name        = p[0],
                address     = p[1],
                port        = p[2].toIntOrNull() ?: return null,
                payloadType = p[3].toIntOrNull() ?: 96,
                encoding    = p[4].ifBlank { "L16" },
                sampleRate  = p[5].toIntOrNull() ?: 48000,
                channels    = p[6].toIntOrNull() ?: 2,
                origin      = RtpSourceOrigin.SAVED
            )
        }
    }
}

/** Esito del parsing: quel che si e' capito, piu' cosa non torna. */
data class SdpParseResult(
    val source: RtpSource?,
    val warnings: List<String> = emptyList(),
    val error: String? = null
) {
    val ok: Boolean get() = source != null && error == null
}

object RtpSdp {

    /** Payload type statici che valgono come descrizione completa (RFC 3551). */
    private val STATIC_PAYLOADS = mapOf(
        0  to Triple("PCMU", 8000, 1),
        8  to Triple("PCMA", 8000, 1),
        10 to Triple("L16", 44100, 2),
        11 to Triple("L16", 44100, 1),
        14 to Triple("MPA", 90000, 1)
    )

    fun isMulticastAddress(addr: String): Boolean {
        val a = addr.substringBefore('/').trim()
        if (a.isEmpty()) return false
        // IPv6: ff00::/8
        if (a.contains(':')) return a.lowercase().startsWith("ff")
        val first = a.substringBefore('.').toIntOrNull() ?: return false
        return first in 224..239
    }

    /**
     * Estrae la configurazione da un descrittore SDP.
     *
     * Si guarda solo il primo blocco `m=audio`: un SDP con piu' media
     * (tipicamente video + audio) resta utilizzabile per la parte audio.
     */
    fun parse(text: String): SdpParseResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return SdpParseResult(null, error = "sdp_err_empty")

        if (lines.none { it.startsWith("m=") })
            return SdpParseResult(null, error = "sdp_err_no_media")

        val warnings = mutableListOf<String>()

        var sessionName = ""
        var sessionAddr = ""       // c= a livello di sessione
        var mediaAddr   = ""       // c= dentro il blocco m=audio
        var port        = -1
        var payloadType = -1
        var encoding    = ""
        var clockRate   = -1
        var channels    = -1

        var inAudio = false
        var sawOtherMedia = false

        for (line in lines) {
            val key = line.take(2)
            val value = line.drop(2)

            when {
                key == "s=" -> sessionName = value.trim()

                key == "c=" -> {
                    val addr = parseConnection(value)
                    if (addr != null) { if (inAudio) mediaAddr = addr else sessionAddr = addr }
                }

                key == "m=" -> {
                    if (value.startsWith("audio")) {
                        if (port >= 0) {          // gia' preso un blocco audio
                            inAudio = false
                            continue
                        }
                        inAudio = true
                        // m=audio <porta> RTP/AVP <pt> [pt...]
                        val parts = value.split(Regex("\\s+"))
                        port = parts.getOrNull(1)?.substringBefore('/')?.toIntOrNull() ?: -1
                        val proto = parts.getOrNull(2) ?: ""
                        if (!proto.startsWith("RTP/")) warnings += "sdp_warn_proto"
                        payloadType = parts.getOrNull(3)?.toIntOrNull() ?: -1
                    } else {
                        inAudio = false
                        sawOtherMedia = true
                    }
                }

                inAudio && line.startsWith("a=rtpmap:") -> {
                    // a=rtpmap:<pt> <encoding>/<clock>[/<channels>]
                    val body = line.removePrefix("a=rtpmap:")
                    val pt = body.substringBefore(' ').toIntOrNull()
                    if (pt == null || payloadType < 0 || pt == payloadType) {
                        val spec = body.substringAfter(' ', "").split('/')
                        if (spec.isNotEmpty() && spec[0].isNotBlank()) encoding = spec[0].trim()
                        spec.getOrNull(1)?.trim()?.toIntOrNull()?.let { clockRate = it }
                        spec.getOrNull(2)?.trim()?.toIntOrNull()?.let { channels = it }
                    }
                }
            }
        }

        if (sawOtherMedia) warnings += "sdp_warn_multi_media"
        if (port < 0)      return SdpParseResult(null, error = "sdp_err_no_audio")
        if (port == 0)     return SdpParseResult(null, error = "sdp_err_port_zero")

        // Payload type statico: completa cio' che manca senza rtpmap.
        if (encoding.isBlank() && payloadType >= 0) {
            STATIC_PAYLOADS[payloadType]?.let { (enc, rate, ch) ->
                encoding = enc
                if (clockRate < 0) clockRate = rate
                if (channels < 0) channels = ch
            }
        }

        val address = mediaAddr.ifBlank { sessionAddr }
        if (address.isBlank()) warnings += "sdp_warn_no_connection"

        if (encoding.isBlank()) { encoding = "L16"; warnings += "sdp_warn_no_rtpmap" }
        if (clockRate < 0)      { clockRate = 48000; warnings += "sdp_warn_no_rate" }
        if (channels < 0)       { channels = if (encoding.equals("L16", true)) 2 else 1 }

        if (channels !in 1..2) warnings += "sdp_warn_channels"

        return SdpParseResult(
            RtpSource(
                name        = sessionName.takeIf { it.isNotBlank() && it != "-" } ?: "",
                address     = address.substringBefore('/'),
                port        = port,
                payloadType = if (payloadType >= 0) payloadType else 96,
                encoding    = encoding,
                sampleRate  = clockRate,
                channels    = channels,
                origin      = RtpSourceOrigin.SDP_PASTE
            ),
            warnings
        )
    }

    /** `c=IN IP4 239.255.0.1/4` → `239.255.0.1/4` (il TTL lo scarta il chiamante). */
    private fun parseConnection(value: String): String? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        if (!parts[0].equals("IN", ignoreCase = true)) return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    /**
     * Ricostruisce un SDP minimo ma valido da una configurazione manuale.
     * Serve al percorso FFmpeg, che vuole un descrittore anche quando
     * l'utente ha compilato i campi a mano.
     */
    fun synthesize(source: RtpSource): String {
        val addr = source.address.ifBlank { "0.0.0.0" }
        val ttl = if (source.isMulticast) "/4" else ""
        return buildString {
            appendLine("v=0")
            appendLine("o=- 0 0 IN IP4 127.0.0.1")
            appendLine("s=${source.displayName()}")
            appendLine("c=IN IP4 $addr$ttl")
            appendLine("t=0 0")
            appendLine("m=audio ${source.port} RTP/AVP ${source.payloadType}")
            appendLine("a=rtpmap:${source.payloadType} ${source.encoding}/${source.sampleRate}/${source.channels}")
            append("a=recvonly")
        }
    }

    /** Controlli che la UI puo' mostrare prima di lasciar premere Ascolta. */
    fun validate(source: RtpSource): List<String> {
        val errs = mutableListOf<String>()
        if (source.port !in 1..65535) errs += "rtp_val_port"
        if (source.sampleRate !in 8000..192000) errs += "rtp_val_rate"
        if (source.channels !in 1..2) errs += "rtp_val_channels"
        if (source.payloadType !in 0..127) errs += "rtp_val_pt"
        if (source.address.isNotBlank() && !looksLikeHost(source.address)) errs += "rtp_val_address"
        return errs
    }

    private fun looksLikeHost(a: String): Boolean {
        val h = a.trim()
        if (h.isEmpty()) return false
        if (h.contains(':')) return h.all { it.isLetterOrDigit() || it == ':' || it == '.' }
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return true
        // nome host
        return h.all { it.isLetterOrDigit() || it == '.' || it == '-' } && h.first().isLetterOrDigit()
    }
}
