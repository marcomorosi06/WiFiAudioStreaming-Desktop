import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket

/*
 * Controlli sul lampeggio dei comandi Snapcast.
 *
 * Il finto server qui dentro imita quello vero nel dettaglio che conta: a ogni
 * comando rispedisce PRIMA una fotografia dello stato scattata prima che il
 * comando arrivasse, poi applica, poi conferma. E' la corsa che faceva saltare
 * avanti e indietro volume e mute, e l'unico modo di provarla e' riprodurla.
 *
 * I comandi vengono dati piu' volte di fila di proposito: il difetto si vedeva
 * dal secondo in poi, non dal primo.
 */

private var snapOvFailures = 0

private fun ovCheck(n: String, c: Boolean, d: String = "") {
    if (c) println("  ok   $n")
    else {
        println("  FAIL $n ${if (d.isNotBlank()) "-> $d" else ""}")
        snapOvFailures++
    }
}

private fun ovStatusJson(percent: Int, muted: Boolean) = """
{"id":1,"jsonrpc":"2.0","result":{"server":{
 "groups":[{"clients":[
   {"config":{"instance":1,"latency":0,"name":"Cucina","volume":{"muted":$muted,"percent":$percent}},
    "connected":true,
    "host":{"arch":"x86_64","ip":"10.0.0.1","mac":"aa:01","name":"cucina","os":"Linux"},
    "id":"aa:01","snapclient":{"name":"Snapclient","protocolVersion":2,"version":"0.27.0"}}],
   "id":"g-1","muted":false,"name":"Piano terra","stream_id":"default"}],
 "server":{"host":{"name":"fake"},"snapserver":{"version":"0.27.0"}},
 "streams":[{"id":"default","status":"playing","uri":{"raw":"pipe:///x"}}]}}}
""".trimIndent().replace("\n", "")

/**
 * Finto server di controllo che si comporta MALE di proposito: dopo un comando
 * rispedisce una fotografia dello stato vecchia, che e' esattamente cio' che
 * provoca il lampeggio.
 */
private class FakeSnapControlServer(private val confirmDelayMs: Long = 120L) {
    val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    @Volatile var setVolumeCount = 0
    @Volatile private var vol = 74
    @Volatile private var muted = false
    private var out: OutputStreamWriter? = null

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        val client = socket.accept()
        val w = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
        out = w
        val r = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        while (isActive) {
            val line = r.readLine() ?: break
            when {
                line.contains("Server.GetStatus") -> send(ovStatusJson(vol, muted))
                line.contains("Client.SetVolume") -> {
                    setVolumeCount++
                    val p = Regex(""""percent":(\d+)""").find(line)?.groupValues?.get(1)?.toInt() ?: vol
                    val m = line.contains(""""muted":true""")
                    // La fotografia in ritardo: porta ANCORA i valori vecchi.
                    send(ovStatusJson(vol, muted))
                    vol = p; muted = m
                    scope.launch(Dispatchers.IO) {
                        delay(confirmDelayMs)
                        send("""{"jsonrpc":"2.0","method":"Client.OnVolumeChanged","params":{"id":"aa:01","volume":{"muted":$m,"percent":$p}}}""")
                    }
                }
            }
        }
    }

    fun send(msg: String) {
        val w = out ?: return
        synchronized(w) { w.write(msg); w.write("\r\n"); w.flush() }
    }

    fun pushStaleSnapshot() = send(ovStatusJson(vol, muted))
}

fun snapcastOverlayChecks() = runBlocking {
    println("== snapcast client: command echo race ==")
    val server = FakeSnapControlServer()
    val scope = CoroutineScope(Dispatchers.IO)
    server.start(scope)

    val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
    var last: SnapControlStatus? = null
    val client = SnapcastControlClient("127.0.0.1", server.port) { st ->
        last = st
        st.status.client("aa:01")?.let { seen.add(it.volumePercent) }
    }
    client.start(scope)

    withTimeoutOrNull(4000) { while (last?.state != SnapControlState.CONNECTED) delay(30) }
    ovCheck("connesso al finto server", last?.state == SnapControlState.CONNECTED, "stato=${last?.state}")
    ovCheck("volume iniziale letto", last?.status?.client("aa:01")?.volumePercent == 74,
        "${last?.status?.client("aa:01")?.volumePercent}")

    // ── PRIMO comando ─────────────────────────────────────────────────────
    seen.clear()
    client.setClientVolume("aa:01", 20, false)
    withTimeoutOrNull(2000) { while (server.setVolumeCount < 1) delay(20) }
    delay(500)
    ovCheck("1o comando: nessun ritorno al valore vecchio", seen.none { it == 74 },
        "valori pubblicati: ${seen.toList()}")
    ovCheck("1o comando: resta sul valore comandato",
        last?.status?.client("aa:01")?.volumePercent == 20,
        "${last?.status?.client("aa:01")?.volumePercent}")

    // ── SECONDO comando: e' qui che si vedeva il difetto ──────────────────
    seen.clear()
    client.setClientVolume("aa:01", 50, false)
    withTimeoutOrNull(2000) { while (server.setVolumeCount < 2) delay(20) }
    delay(500)
    ovCheck("2o comando: nessun ritorno al valore precedente", seen.none { it == 20 },
        "valori pubblicati: ${seen.toList()}")
    ovCheck("2o comando: resta sul valore comandato",
        last?.status?.client("aa:01")?.volumePercent == 50,
        "${last?.status?.client("aa:01")?.volumePercent}")

    // ── TERZO, subito dopo il secondo senza pause ─────────────────────────
    seen.clear()
    client.setClientVolume("aa:01", 80, false)
    client.setClientVolume("aa:01", 35, false)
    withTimeoutOrNull(2000) { while (server.setVolumeCount < 4) delay(20) }
    delay(600)
    ovCheck("due comandi ravvicinati: vince l'ultimo",
        last?.status?.client("aa:01")?.volumePercent == 35,
        "${last?.status?.client("aa:01")?.volumePercent}")

    // ── Una fotografia vecchia che arriva spontanea ───────────────────────
    seen.clear()
    client.setClientVolume("aa:01", 10, false)
    delay(30)
    server.pushStaleSnapshot()
    delay(500)
    ovCheck("fotografia spontanea non riporta indietro", seen.none { it == 35 },
        "valori pubblicati: ${seen.toList()}")

    // ── Il mute, due volte di fila ────────────────────────────────────────
    client.setClientVolume("aa:01", 10, true)
    delay(400)
    ovCheck("1o mute applicato", last?.status?.client("aa:01")?.muted == true)
    client.setClientVolume("aa:01", 10, false)
    delay(400)
    ovCheck("2o mute (smute) applicato", last?.status?.client("aa:01")?.muted == false,
        "muted=${last?.status?.client("aa:01")?.muted}")
    client.setClientVolume("aa:01", 10, true)
    delay(400)
    ovCheck("3o mute applicato", last?.status?.client("aa:01")?.muted == true,
        "muted=${last?.status?.client("aa:01")?.muted}")

    // ── Il gruppo ─────────────────────────────────────────────────────────
    client.setGroupMute("g-1", true)
    delay(300)
    ovCheck("il mute di gruppo non torna indietro",
        last?.status?.groups?.first()?.muted == true)

    client.stop(); scope.cancel(); runCatching { server.socket.close() }
    delay(200)
    if (snapOvFailures > 0) {
        println("  $snapOvFailures controlli falliti")
        kotlin.system.exitProcess(1)
    }
}
