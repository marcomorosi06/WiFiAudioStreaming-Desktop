    /* ── Intenzioni in volo ────────────────────────────────────────────────
     *
     * Quando si comanda qualcosa, il server risponde con la sua notifica — ma
     * puo' anche spedire, nello stesso momento, una fotografia dello stato
     * scattata PRIMA che il comando arrivasse. Applicarla riporta il controllo
     * al valore vecchio, e un attimo dopo la notifica lo rimanda avanti: e'
     * quello che si vede come lampeggio.
     *
     * La cura non puo' stare nella UI, perche' li' andrebbe ripetuta per ogni
     * singolo controllo — volume, mute, latenza, nome, mute di gruppo, stream —
     * e ne dimenticheresti sempre uno. Sta qui: quello che abbiamo appena
     * chiesto vince su qualunque cosa arrivi, finche' il server non conferma
     * esattamente quel valore o finche' non scade il tempo.
     *
     * Lo stato "vero" del server resta intatto in [current]: la sovrapposizione
     * si applica solo al momento di pubblicare, quindi appena l'intenzione
     * scade o viene confermata si torna a mostrare la verita' senza scatti.
     * ─────────────────────────────────────────────────────────────────────── */

    private data class ClientIntent(
        val volumePercent: Int? = null,
        val muted: Boolean? = null,
        val latencyMs: Int? = null,
        val name: String? = null,
        val expiresAt: Long
    )

    private data class GroupIntent(
        val muted: Boolean? = null,
        val streamId: String? = null,
        val name: String? = null,
        val expiresAt: Long
    )

    private val clientIntents = ConcurrentHashMap<String, ClientIntent>()
    private val groupIntents = ConcurrentHashMap<String, GroupIntent>()

    private fun deadline() = System.currentTimeMillis() + INTENT_TTL_MS

    private fun noteClient(id: String, block: (ClientIntent) -> ClientIntent) {
        val base = clientIntents[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: ClientIntent(expiresAt = deadline())
        clientIntents[id] = block(base).copy(expiresAt = deadline())
        publish(SnapControlState.CONNECTED)
    }

    private fun noteGroup(id: String, block: (GroupIntent) -> GroupIntent) {
        val base = groupIntents[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: GroupIntent(expiresAt = deadline())
        groupIntents[id] = block(base).copy(expiresAt = deadline())
        publish(SnapControlState.CONNECTED)
    }

    /** Toglie le intenzioni che il server ha gia' recepito: da li' comanda lui. */
    private fun settle(truth: SnapServerStatus) {
        clientIntents.entries.removeIf { (id, i) ->
            val c = truth.client(id) ?: return@removeIf false
            (i.volumePercent == null || i.volumePercent == c.volumePercent) &&
            (i.muted == null || i.muted == c.muted) &&
            (i.latencyMs == null || i.latencyMs == c.latencyMs) &&
            (i.name == null || i.name == c.name)
        }
        groupIntents.entries.removeIf { (id, i) ->
            val g = truth.groups.firstOrNull { it.id == id } ?: return@removeIf false
            (i.muted == null || i.muted == g.muted) &&
            (i.streamId == null || i.streamId == g.streamId) &&
            (i.name == null || i.name == g.name)
        }
    }

    private fun overlay(truth: SnapServerStatus): SnapServerStatus {
        val now = System.currentTimeMillis()
        clientIntents.entries.removeIf { it.value.expiresAt <= now }
        groupIntents.entries.removeIf { it.value.expiresAt <= now }
        if (clientIntents.isEmpty() && groupIntents.isEmpty()) return truth

        return truth.copy(groups = truth.groups.map { g ->
            val gi = groupIntents[g.id]
            val merged = if (gi == null) g else g.copy(
                muted = gi.muted ?: g.muted,
                streamId = gi.streamId ?: g.streamId,
                name = gi.name ?: g.name
            )
            merged.copy(clients = merged.clients.map { c ->
                val ci = clientIntents[c.id] ?: return@map c
                c.copy(
                    volumePercent = ci.volumePercent ?: c.volumePercent,
                    muted = ci.muted ?: c.muted,
                    latencyMs = ci.latencyMs ?: c.latencyMs,
                    name = ci.name ?: c.name
                )
            })
        })
    }

    /** Unico punto da cui lo stato esce verso la UI. */
    private fun publish(
        state: SnapControlState,
        detail: String? = null
    ) = onStatus(
        SnapControlStatus(state, overlay(current), detail, host, port, attempts.get())
    )

    // ── Comandi ────────────────────────────────────────────────────────────

    fun setClientVolume(clientId: String, percent: Int, muted: Boolean) = runCatching {
        val p = percent.coerceIn(0, 100)
        request("Client.SetVolume", SnapJsonWriter.write {
            put("id", clientId)
            obj("volume") {
                put("muted", muted)
                put("percent", p)
            }
        })
        noteClient(clientId) { it.copy(volumePercent = p, muted = muted) }
    }.isSuccess

    fun setClientName(clientId: String, name: String) = runCatching {
        request("Client.SetName", SnapJsonWriter.write {
            put("id", clientId); put("name", name)
        })
        noteClient(clientId) { it.copy(name = name) }
    }.isSuccess

    fun setClientLatency(clientId: String, latencyMs: Int) = runCatching {
        val l = latencyMs.coerceIn(-2000, 2000)
        request("Client.SetLatency", SnapJsonWriter.write {
            put("id", clientId); put("latency", l)
        })
        noteClient(clientId) { it.copy(latencyMs = l) }
    }.isSuccess

    fun setGroupMute(groupId: String, mute: Boolean) = runCatching {
        request("Group.SetMute", SnapJsonWriter.write {
            put("id", groupId); put("mute", mute)
        })
        noteGroup(groupId) { it.copy(muted = mute) }
    }.isSuccess

    fun setGroupName(groupId: String, name: String) = runCatching {
        request("Group.SetName", SnapJsonWriter.write {
            put("id", groupId); put("name", name)
        })
        noteGroup(groupId) { it.copy(name = name) }
    }.isSuccess

    fun setGroupStream(groupId: String, streamId: String) = runCatching {
        request("Group.SetStream", SnapJsonWriter.write {
            put("id", groupId); put("stream_id", streamId)
        })
        noteGroup(groupId) { it.copy(streamId = streamId) }
    }.isSuccess

    fun setGroupClients(groupId: String, clientIds: List<String>) = runCatching {
        request("Group.SetClients", SnapJsonWriter.write {
            put("id", groupId)
            arrayOfStrings("clients", clientIds)
        })
    }.isSuccess

    fun refresh() = runCatching { request("Server.GetStatus", null) }.isSuccess
