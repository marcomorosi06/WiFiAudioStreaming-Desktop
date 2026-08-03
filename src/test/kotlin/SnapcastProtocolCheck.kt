import kotlin.random.Random

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun snapcastProtocolChecks() {
    println("== snapcast wire format ==")

    val baseOnly = SnapcastWire.frame(
        SnapcastMessageType.TIME, 0x1234, 0x5678,
        SnapcastTv(7, 250000), SnapcastTv(0, 0), ByteArray(0)
    )
    eq("base message size", baseOnly.size, SnapcastWire.BASE_SIZE)
    eq(
        "base message bytes",
        hex(baseOnly),
        "0400341278560700000090d00300000000000000000000000000"
    )

    val settings = SnapcastWire.frame(
        SnapcastMessageType.SERVER_SETTINGS, 1, 0,
        SnapcastTv(12, 34), SnapcastTv(0, 0),
        SnapcastWire.stringPayload("{\"bufferMs\":1000,\"latency\":0,\"muted\":false,\"volume\":100}")
    )
    eq(
        "server settings bytes",
        hex(settings),
        "0300010000000c0000002200000000000000000000003c000000380000007b226275666665724d73223a31303030" +
            "2c226c6174656e6379223a302c226d75746564223a66616c73652c22766f6c756d65223a3130307d"
    )

    val codecHeader = SnapcastWire.frame(
        SnapcastMessageType.CODEC_HEADER, 2, 0,
        SnapcastTv(0, 0), SnapcastTv(0, 0),
        SnapcastWire.codecHeaderPayload("pcm", SnapcastWire.wavHeader(48000, 16, 2))
    )
    eq(
        "pcm codec header bytes",
        hex(codecHeader),
        "01000200000000000000000000000000000000000000370000000300000070636d2c00000052494646240000005741" +
            "5645666d7420100000000100020080bb000000ee0200040010006461746100000000"
    )

    val chunk = SnapcastWire.frame(
        SnapcastMessageType.WIRE_CHUNK, 3, 0,
        SnapcastTv(5, 6), SnapcastTv(0, 0),
        SnapcastWire.wireChunkPayload(SnapcastTv(1, 500000), ByteArray(8) { it.toByte() }, 0, 8)
    )
    eq(
        "wire chunk bytes",
        hex(chunk),
        "0200030000000500000006000000000000000000000014000000010000002" +
            "0a10700080000000001020304050607"
    )

    val timeReply = SnapcastWire.frame(
        SnapcastMessageType.TIME, 9, 0x00AB,
        SnapcastTv(2, 3), SnapcastTv(2, 1),
        SnapcastWire.timePayload(SnapcastTv(0, 1234))
    )
    eq(
        "time reply bytes",
        hex(timeReply),
        "04000900ab00020000000300000002000000010000000800000000000000d2040000"
    )

    val error = SnapcastWire.frame(
        SnapcastMessageType.ERROR, 4, 0,
        SnapcastTv(0, 0), SnapcastTv(0, 0),
        SnapcastWire.errorPayload(SnapcastErrorCode.AUTH_FAILED, "auth", "nope")
    )
    eq(
        "error bytes",
        hex(error),
        "0800040000000000000000000000000000000000000014000000010000000400000061757468040000006e6f7065"
    )

    eq("opus pseudo header", hex(SnapcastWire.opusHeader(48000, 16, 2)), "5355504f80bb000010000200")
    eq(
        "wav header mono 44100",
        hex(SnapcastWire.wavHeader(44100, 16, 1)),
        "524946462400000057415645666d7420100000000100010044ac000088580100020010006461746100000000"
    )
    eq(
        "flac header wrapping",
        hex(SnapcastWire.flacHeader(ByteArray(34) { it.toByte() })),
        "664c614380000022000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021"
    )
    check("flac header rejects short streaminfo", SnapcastWire.flacHeader(ByteArray(10)).isEmpty())

    println("== snapcast header round trip ==")
    val parsed = SnapcastWire.parseHeader(timeReply)
    eq("parsed type", parsed.type, SnapcastMessageType.TIME)
    eq("parsed id", parsed.id, 9)
    eq("parsed refersTo", parsed.refersTo, 0x00AB)
    eq("parsed sent", parsed.sent, SnapcastTv(2, 3))
    eq("parsed received", parsed.received, SnapcastTv(2, 1))
    eq("parsed size", parsed.size, 8)
    eq("parsed time payload", SnapcastWire.parseTimePayload(timeReply.copyOfRange(26, 34)), SnapcastTv(0, 1234))

    val hello = SnapcastWire.stringPayload("{\"ID\":\"aa:bb\",\"Instance\":2}")
    eq("string payload round trip", SnapcastWire.readString(hello), "{\"ID\":\"aa:bb\",\"Instance\":2}")

    val random = Random(7)
    repeat(200) {
        val payload = ByteArray(random.nextInt(0, 300)) { random.nextInt(256).toByte() }
        val type = random.nextInt(0, 9)
        val id = random.nextInt(0, 65536)
        val refers = random.nextInt(0, 65536)
        val sent = SnapcastTv(random.nextInt(-1000, 100000), random.nextInt(0, 1000000))
        val recv = SnapcastTv(random.nextInt(-1000, 100000), random.nextInt(0, 1000000))
        val encoded = SnapcastWire.frame(type, id, refers, sent, recv, payload)
        val header = SnapcastWire.parseHeader(encoded)
        if (header.type != type || header.id != id || header.refersTo != refers ||
            header.sent != sent || header.received != recv || header.size != payload.size ||
            !encoded.copyOfRange(26, encoded.size).contentEquals(payload)
        ) {
            check("fuzz round trip iteration $it", false, "header=$header")
            return@repeat
        }
    }
    check("fuzz round trip 200 frames", true)

    println("== snapcast time arithmetic ==")
    eq("micros to tv", SnapcastTv.fromMicros(1_500_000L), SnapcastTv(1, 500000))
    eq("negative micros to tv", SnapcastTv.fromMicros(-1L), SnapcastTv(-1, 999999))
    eq("tv to micros", SnapcastTv(3, 250000).toMicros(), 3_250_000L)
    eq("negative tv round trip", SnapcastTv.fromMicros(-1L).toMicros(), -1L)

    println("== snapcast stream clock ==")
    val clock = SnapcastSteadyClock()
    val streamClock = SnapcastStreamClock(clock, 48000)
    val first = streamClock.timestampFor(960)
    val second = streamClock.timestampFor(960)
    val third = streamClock.timestampFor(960)
    val deltaOne = second.toMicros() - first.toMicros()
    val deltaTwo = third.toMicros() - second.toMicros()
    check(
        "chunk timestamps advance by about 20 ms",
        deltaOne in 19_000..21_000 && deltaTwo in 19_000..21_000,
        "delta1=$deltaOne delta2=$deltaTwo"
    )
    streamClock.reset()
    val afterReset = streamClock.timestampFor(960)
    check(
        "reset re-anchors the clock",
        afterReset.toMicros() >= 0L,
        "value=${afterReset.toMicros()}"
    )

    println("== snapcast audio format ==")
    val format = SnapcastAudioFormat(48000, 16, 2)
    eq("frame bytes", format.frameBytes, 4)
    eq("bytes per ms", format.bytesPerMs, 192)
    eq("frames of a 20 ms chunk", format.framesOf(192 * 20), 960)
    eq("sample format string", format.describe(), "48000:16:2")
    eq("codec normalise unknown", SnapcastCodecs.normalize("mp3"), SnapcastCodecs.PCM)
    eq("codec normalise case", SnapcastCodecs.normalize("FLAC"), SnapcastCodecs.FLAC)
    eq("codec normalise null", SnapcastCodecs.normalize(null), SnapcastCodecs.PCM)

    println("== snapcast json ==")
    val doc = SnapJson.parse(
        """{"a":1,"b":"x\"y","c":[1,2,{"d":true}],"e":null,"f":-2.5,"g":{"h":false}}"""
    )
    eq("json int", doc.intAt("a"), 1)
    eq("json escaped string", doc.stringAt("b"), "x\"y")
    eq("json nested array size", doc.field("c").asArray().size, 3)
    eq("json nested object bool", doc.field("c").asArray()[2].boolAt("d"), true)
    eq("json null field", doc.field("e"), SnapJson.Null)
    eq("json negative double", (doc.field("f") as SnapJson.Num).value, -2.5)
    eq("json deep bool", doc.field("g").boolAt("h"), false)
    eq("json missing key", doc.stringAt("zzz"), null)
    check("json rejects garbage", SnapJson.parse("{ not json") == null)
    check("json rejects truncated", SnapJson.parse("""{"a":""") == null)

    val written = SnapJsonWriter.write {
        put("name", "a\"b\\c")
        put("count", 3)
        put("big", 9_000_000_000L)
        put("flag", true)
        obj("nested") {
            put("inner", "v")
        }
        arrayOfStrings("list", listOf("x", "y"))
        rawArray("raw", listOf("{\"k\":1}", "2"))
        putNull("empty")
    }
    eq(
        "json writer output",
        written,
        """{"name":"a\"b\\c","count":3,"big":9000000000,"flag":true,"nested":{"inner":"v"},""" +
            """"list":["x","y"],"raw":[{"k":1},2],"empty":null}"""
    )
    val reparsed = SnapJson.parse(written)
    eq("writer output reparses", reparsed.stringAt("name"), "a\"b\\c")
    eq("writer nested reparses", reparsed.field("nested").stringAt("inner"), "v")

    println("== snapcast protocol summary ==")
    eq(
        "all five protocols",
        ProtocolStatus.summary(true, true, true, true, "and", true),
        "WFAS, RTP, HTTP, DLNA and Snapcast"
    )
    eq(
        "snapcast only",
        ProtocolStatus.summary(false, false, false, false, "and", true),
        "Snapcast"
    )
    eq(
        "snapcast defaults to off",
        ProtocolStatus.summary(true, false, false, false, "and"),
        "WFAS"
    )

    println("== snapcast policy ==")
    check(
        "snapcast alone can start the server",
        WfasPolicy.canStartServerWith(
            WfasPolicy.MODE_OFF, usbReady = false, rtpEnabled = false,
            httpEnabled = false, dlnaEnabled = false, snapcastEnabled = true
        )
    )
    check(
        "no protocol still refuses",
        !WfasPolicy.canStartServerWith(
            WfasPolicy.MODE_OFF, usbReady = false, rtpEnabled = false,
            httpEnabled = false, dlnaEnabled = false, snapcastEnabled = false
        )
    )

    println("== snapcast state model ==")
    val host = SnapcastHostInfo("desk", "aa:bb:cc:dd:ee:ff", "Linux", "x86_64", "192.168.1.2")
    val state = SnapcastState(host, "5.1.0", null, "default")
    state.updateStream(SnapcastCodecs.FLAC, "48000:16:2", 20, "playing")
    val client = state.onClientConnected(
        id = "11:22:33:44:55:66",
        host = SnapcastHostInfo("pi", "11:22:33:44:55:66", "Linux", "aarch64", "192.168.1.9"),
        clientName = "Snapclient",
        version = "0.31.0",
        protocolVersion = 2,
        instance = 1
    )
    eq("client registered as connected", client.connected, true)
    eq("connected count", state.connectedCount(), 1)
    val group = state.groupIdOfClient("11:22:33:44:55:66")
    check("client landed in a group", group != null)
    state.setClientVolume("11:22:33:44:55:66", 42, false)
    eq("volume stored", state.clientSnapshot("11:22:33:44:55:66")?.config?.volumePercent, 42)
    eq("effective volume unmuted", state.effectiveVolume("11:22:33:44:55:66"), 42 to false)
    state.setGroupMuted(group!!, true)
    eq("group mute wins", state.effectiveVolume("11:22:33:44:55:66"), 42 to true)
    state.setClientVolume("11:22:33:44:55:66", 150, false)
    eq("volume clamped to 100", state.clientSnapshot("11:22:33:44:55:66")?.config?.volumePercent, 100)
    state.onClientDisconnected("11:22:33:44:55:66")
    eq("disconnect flips the flag", state.connectedCount(), 0)
    check("client is remembered after disconnect", state.allClients().size == 1)

    val status = SnapJson.parse(state.serverStatusJson())
    check("status has a server object", status.field("server") != null)
    eq(
        "status advertises the stream protocol version",
        status.field("server").field("server").field("snapserver").intAt("protocolVersion"),
        2
    )
    eq(
        "status advertises one stream",
        status.field("server").field("streams").asArray().size,
        1
    )
    eq(
        "status stream carries the codec",
        status.field("server").field("streams").asArray()[0]
            .field("uri").field("query").stringAt("codec"),
        SnapcastCodecs.FLAC
    )
    eq(
        "status groups are listed",
        status.field("server").field("groups").asArray().size,
        1
    )
    check("removing a client works", state.removeClient("11:22:33:44:55:66"))
    eq("group is dropped with its last client", state.groupsJson().size, 0)
}
