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
import java.util.UUID

data class SnapcastHostInfo(
    val name: String,
    val mac: String,
    val os: String,
    val arch: String,
    val ip: String
) {

    fun toJson(): String = SnapJsonWriter.write {
        put("arch", arch)
        put("ip", ip)
        put("mac", mac)
        put("name", name)
        put("os", os)
    }
}

data class SnapcastClientConfig(
    val name: String = "",
    val latency: Int = 0,
    val volumePercent: Int = 100,
    val muted: Boolean = false
)

data class SnapcastClient(
    val id: String,
    val host: SnapcastHostInfo,
    val clientName: String = "Snapclient",
    val version: String = "",
    val protocolVersion: Int = SnapcastWire.STREAM_PROTOCOL_VERSION,
    val instance: Int = 1,
    val config: SnapcastClientConfig = SnapcastClientConfig(),
    val connected: Boolean = false,
    val lastSeenSec: Long = 0L,
    val lastSeenUsec: Long = 0L
) {

    fun toJson(): String = SnapJsonWriter.write {
        obj("config") {
            put("instance", instance)
            put("latency", config.latency)
            put("name", config.name)
            obj("volume") {
                put("muted", config.muted)
                put("percent", config.volumePercent)
            }
        }
        put("connected", connected)
        putRaw("host", host.toJson())
        put("id", id)
        obj("lastSeen") {
            put("sec", lastSeenSec)
            put("usec", lastSeenUsec)
        }
        obj("snapclient") {
            put("name", clientName)
            put("protocolVersion", protocolVersion)
            put("version", version)
        }
    }
}

data class SnapcastGroup(
    val id: String,
    val name: String = "",
    val muted: Boolean = false,
    val streamId: String,
    val clientIds: List<String> = emptyList()
)

data class SnapcastStreamInfo(
    val id: String,
    val status: String,
    val codec: String,
    val sampleFormat: String,
    val chunkMs: Int
) {

    fun toJson(): String = SnapJsonWriter.write {
        put("id", id)
        put("status", status)
        obj("uri") {
            put("fragment", "")
            put("host", "")
            put("path", "")
            obj("query") {
                put("chunk_ms", chunkMs.toString())
                put("codec", codec)
                put("name", id)
                put("sampleformat", sampleFormat)
            }
            put("raw", "wfas:///$id?codec=$codec&name=$id&sampleformat=$sampleFormat")
            put("scheme", "wfas")
        }
    }
}

interface SnapcastStateListener {

    fun onClientSettingsChanged(clientId: String)

    fun onServerUpdate()

    fun onNotification(method: String, paramsJson: String)
}

class SnapcastState(
    private val serverHost: SnapcastHostInfo,
    private val serverVersion: String,
    private val persistenceFile: File?,
    val streamId: String = "default"
) {

    private val lock = Any()
    private val clients = LinkedHashMap<String, SnapcastClient>()
    private val groups = LinkedHashMap<String, SnapcastGroup>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SnapcastStateListener>()

    @Volatile
    private var stream = SnapcastStreamInfo(streamId, "idle", SnapcastCodecs.PCM, "48000:16:2", 20)

    init {
        loadPersisted()
    }

    fun addListener(listener: SnapcastStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SnapcastStateListener) {
        listeners.remove(listener)
    }

    fun updateStream(codec: String, sampleFormat: String, chunkMs: Int, status: String) {
        stream = SnapcastStreamInfo(streamId, status, codec, sampleFormat, chunkMs)
        notifyServerUpdate()
        broadcast("Stream.OnUpdate", SnapJsonWriter.write {
            put("id", streamId)
            putRaw("stream", stream.toJson())
        })
    }

    fun streamInfo(): SnapcastStreamInfo = stream

    fun clientSnapshot(id: String): SnapcastClient? = synchronized(lock) { clients[id] }

    fun connectedClients(): List<SnapcastClient> = synchronized(lock) { clients.values.filter { it.connected } }

    fun allClients(): List<SnapcastClient> = synchronized(lock) { clients.values.toList() }

    fun connectedCount(): Int = synchronized(lock) { clients.values.count { it.connected } }

    fun groupOf(clientId: String): SnapcastGroup? =
        synchronized(lock) { groups.values.firstOrNull { it.clientIds.contains(clientId) } }

    fun effectiveVolume(clientId: String): Pair<Int, Boolean> {
        synchronized(lock) {
            val client = clients[clientId] ?: return 100 to false
            val group = groups.values.firstOrNull { it.clientIds.contains(clientId) }
            val muted = client.config.muted || (group?.muted ?: false)
            return client.config.volumePercent to muted
        }
    }

    fun onClientConnected(
        id: String,
        host: SnapcastHostInfo,
        clientName: String,
        version: String,
        protocolVersion: Int,
        instance: Int
    ): SnapcastClient {
        val updated: SnapcastClient
        synchronized(lock) {
            val existing = clients[id]
            val config = existing?.config ?: SnapcastClientConfig(name = host.name)
            updated = SnapcastClient(
                id = id,
                host = host,
                clientName = clientName,
                version = version,
                protocolVersion = protocolVersion,
                instance = instance,
                config = config,
                connected = true,
                lastSeenSec = System.currentTimeMillis() / 1000L,
                lastSeenUsec = (System.currentTimeMillis() % 1000L) * 1000L
            )
            clients[id] = updated
            if (groups.values.none { it.clientIds.contains(id) }) {
                val groupId = UUID.randomUUID().toString()
                groups[groupId] = SnapcastGroup(id = groupId, streamId = streamId, clientIds = listOf(id))
            }
        }
        persist()
        broadcast("Client.OnConnect", SnapJsonWriter.write {
            put("id", id)
            putRaw("client", updated.toJson())
        })
        notifyServerUpdate()
        return updated
    }

    fun onClientDisconnected(id: String) {
        val updated: SnapcastClient
        synchronized(lock) {
            val existing = clients[id] ?: return
            val now = System.currentTimeMillis()
            updated = existing.copy(
                connected = false,
                lastSeenSec = now / 1000L,
                lastSeenUsec = (now % 1000L) * 1000L
            )
            clients[id] = updated
        }
        persist()
        broadcast("Client.OnDisconnect", SnapJsonWriter.write {
            put("id", id)
            putRaw("client", updated.toJson())
        })
        notifyServerUpdate()
    }

    fun setClientVolume(id: String, percent: Int, muted: Boolean): SnapcastClient? {
        val updated: SnapcastClient
        synchronized(lock) {
            val existing = clients[id] ?: return null
            updated = existing.copy(
                config = existing.config.copy(volumePercent = percent.coerceIn(0, 100), muted = muted)
            )
            clients[id] = updated
        }
        persist()
        broadcast("Client.OnVolumeChanged", SnapJsonWriter.write {
            put("id", id)
            obj("volume") {
                put("muted", updated.config.muted)
                put("percent", updated.config.volumePercent)
            }
        })
        listeners.forEach { runCatching { it.onClientSettingsChanged(id) } }
        return updated
    }

    fun setClientLatency(id: String, latency: Int): SnapcastClient? {
        val updated: SnapcastClient
        synchronized(lock) {
            val existing = clients[id] ?: return null
            updated = existing.copy(config = existing.config.copy(latency = latency))
            clients[id] = updated
        }
        persist()
        broadcast("Client.OnLatencyChanged", SnapJsonWriter.write {
            put("id", id)
            put("latency", latency)
        })
        listeners.forEach { runCatching { it.onClientSettingsChanged(id) } }
        return updated
    }

    fun setClientName(id: String, name: String): SnapcastClient? {
        val updated: SnapcastClient
        synchronized(lock) {
            val existing = clients[id] ?: return null
            updated = existing.copy(config = existing.config.copy(name = name))
            clients[id] = updated
        }
        persist()
        broadcast("Client.OnNameChanged", SnapJsonWriter.write {
            put("id", id)
            put("name", name)
        })
        notifyServerUpdate()
        return updated
    }

    fun removeClient(id: String): Boolean {
        synchronized(lock) {
            if (clients.remove(id) == null) return false
            val emptied = ArrayList<String>()
            val rewritten = ArrayList<Pair<String, SnapcastGroup>>()
            groups.forEach { (groupId, group) ->
                if (group.clientIds.contains(id)) {
                    val remaining = group.clientIds - id
                    if (remaining.isEmpty()) emptied.add(groupId)
                    else rewritten.add(groupId to group.copy(clientIds = remaining))
                }
            }
            rewritten.forEach { (groupId, group) -> groups[groupId] = group }
            emptied.forEach { groups.remove(it) }
        }
        persist()
        notifyServerUpdate()
        return true
    }

    fun setGroupMuted(groupId: String, muted: Boolean): SnapcastGroup? {
        val updated: SnapcastGroup
        val affected: List<String>
        synchronized(lock) {
            val existing = groups[groupId] ?: return null
            updated = existing.copy(muted = muted)
            groups[groupId] = updated
            affected = updated.clientIds
        }
        persist()
        broadcast("Group.OnMute", SnapJsonWriter.write {
            put("id", groupId)
            put("mute", muted)
        })
        affected.forEach { clientId -> listeners.forEach { runCatching { it.onClientSettingsChanged(clientId) } } }
        return updated
    }

    fun setGroupName(groupId: String, name: String): SnapcastGroup? {
        val updated: SnapcastGroup
        synchronized(lock) {
            val existing = groups[groupId] ?: return null
            updated = existing.copy(name = name)
            groups[groupId] = updated
        }
        persist()
        broadcast("Group.OnNameChanged", SnapJsonWriter.write {
            put("id", groupId)
            put("name", name)
        })
        notifyServerUpdate()
        return updated
    }

    fun setGroupStream(groupId: String, requestedStreamId: String): SnapcastGroup? {
        val updated: SnapcastGroup
        synchronized(lock) {
            val existing = groups[groupId] ?: return null
            if (requestedStreamId != streamId) return null
            updated = existing.copy(streamId = requestedStreamId)
            groups[groupId] = updated
        }
        persist()
        broadcast("Group.OnStreamChanged", SnapJsonWriter.write {
            put("id", groupId)
            put("stream_id", requestedStreamId)
        })
        notifyServerUpdate()
        return updated
    }

    fun setGroupClients(groupId: String, clientIds: List<String>): Boolean {
        synchronized(lock) {
            val target = groups[groupId] ?: return false
            val known = clientIds.filter { clients.containsKey(it) }
            val emptied = ArrayList<String>()
            val rewritten = ArrayList<Pair<String, SnapcastGroup>>()
            groups.forEach { (otherId, group) ->
                if (otherId == groupId) return@forEach
                val remaining = group.clientIds.filterNot { known.contains(it) }
                if (remaining.isEmpty()) emptied.add(otherId)
                else if (remaining.size != group.clientIds.size) {
                    rewritten.add(otherId to group.copy(clientIds = remaining))
                }
            }
            rewritten.forEach { (otherId, group) -> groups[otherId] = group }
            emptied.forEach { groups.remove(it) }
            if (known.isEmpty()) groups.remove(groupId)
            else groups[groupId] = target.copy(clientIds = known)
        }
        persist()
        notifyServerUpdate()
        return true
    }

    fun groupJson(groupId: String): String? {
        synchronized(lock) {
            val group = groups[groupId] ?: return null
            val members = group.clientIds.mapNotNull { clients[it] }
            return SnapJsonWriter.write {
                rawArray("clients", members.map { it.toJson() })
                put("id", group.id)
                put("muted", group.muted)
                put("name", group.name)
                put("stream_id", group.streamId)
            }
        }
    }

    fun groupIdOfClient(clientId: String): String? =
        synchronized(lock) { groups.values.firstOrNull { it.clientIds.contains(clientId) }?.id }

    fun groupsJson(): List<String> = synchronized(lock) {
        groups.values.map { group ->
            val members = group.clientIds.mapNotNull { clients[it] }
            SnapJsonWriter.write {
                rawArray("clients", members.map { it.toJson() })
                put("id", group.id)
                put("muted", group.muted)
                put("name", group.name)
                put("stream_id", group.streamId)
            }
        }
    }

    fun serverStatusJson(): String = SnapJsonWriter.write {
        putRaw("server", serverStatusInner())
    }

    private fun notifyServerUpdate() {
        listeners.forEach { runCatching { it.onServerUpdate() } }
        broadcast("Server.OnUpdate", SnapJsonWriter.write {
            putRaw("server", serverStatusInner())
        })
    }

    private fun serverStatusInner(): String = SnapJsonWriter.write {
        rawArray("groups", groupsJson())
        obj("server") {
            putRaw("host", serverHost.toJson())
            obj("snapserver") {
                put("controlProtocolVersion", SnapcastWire.CONTROL_PROTOCOL_VERSION)
                put("name", "Snapserver")
                put("protocolVersion", SnapcastWire.STREAM_PROTOCOL_VERSION)
                put("version", serverVersion)
            }
        }
        rawArray("streams", listOf(stream.toJson()))
    }

    private fun broadcast(method: String, paramsJson: String) {
        listeners.forEach { runCatching { it.onNotification(method, paramsJson) } }
    }

    private fun persist() {
        val file = persistenceFile ?: return
        val payload = synchronized(lock) {
            SnapJsonWriter.write {
                rawArray("clients", clients.values.map { client ->
                    SnapJsonWriter.write {
                        put("id", client.id)
                        put("name", client.config.name)
                        put("latency", client.config.latency)
                        put("volume", client.config.volumePercent)
                        put("muted", client.config.muted)
                        put("hostName", client.host.name)
                        put("hostMac", client.host.mac)
                        put("hostOs", client.host.os)
                        put("hostArch", client.host.arch)
                        put("hostIp", client.host.ip)
                    }
                })
                rawArray("groups", groups.values.map { group ->
                    SnapJsonWriter.write {
                        put("id", group.id)
                        put("name", group.name)
                        put("muted", group.muted)
                        put("stream_id", group.streamId)
                        arrayOfStrings("clients", group.clientIds)
                    }
                })
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun loadPersisted() {
        val file = persistenceFile ?: return
        if (!file.exists()) return
        val root = runCatching { SnapJson.parse(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        root.field("clients").asArray().forEach { entry ->
            val id = entry.stringAt("id") ?: return@forEach
            clients[id] = SnapcastClient(
                id = id,
                host = SnapcastHostInfo(
                    name = entry.stringAt("hostName") ?: "",
                    mac = entry.stringAt("hostMac") ?: "",
                    os = entry.stringAt("hostOs") ?: "",
                    arch = entry.stringAt("hostArch") ?: "",
                    ip = entry.stringAt("hostIp") ?: ""
                ),
                config = SnapcastClientConfig(
                    name = entry.stringAt("name") ?: "",
                    latency = entry.intAt("latency") ?: 0,
                    volumePercent = entry.intAt("volume") ?: 100,
                    muted = entry.boolAt("muted") ?: false
                ),
                connected = false
            )
        }
        root.field("groups").asArray().forEach { entry ->
            val id = entry.stringAt("id") ?: return@forEach
            groups[id] = SnapcastGroup(
                id = id,
                name = entry.stringAt("name") ?: "",
                muted = entry.boolAt("muted") ?: false,
                streamId = entry.stringAt("stream_id") ?: streamId,
                clientIds = entry.field("clients").asArray().mapNotNull { it.asString() }
            )
        }
    }
}
