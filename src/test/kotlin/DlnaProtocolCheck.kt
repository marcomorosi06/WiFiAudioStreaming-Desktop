import kotlin.system.exitProcess

var failures = 0

fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) println("  ok   $name")
    else { println("  FAIL $name ${if (detail.isNotBlank()) "-> $detail" else ""}"); failures++ }
}

fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "actual=$actual expected=$expected")

val denonDescription = """
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>Denon AVR-X2800H</friendlyName>
    <manufacturer>Denon</manufacturer>
    <modelName>AVR-X2800H</modelName>
    <UDN>uuid:5f9ec1b3-ed59-79bb-4530-0005cdaf1234</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>RenderingControl/ctrl</controlURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/upnp/control/ConnectionManager1</controlURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/upnp/control/AVTransport1</controlURL>
      </service>
    </serviceList>
  </device>
</root>
""".trimIndent()

val prefixedDescription = """
<?xml version="1.0"?>
<u:root xmlns:u="urn:schemas-upnp-org:device-1-0">
  <u:URLBase>http://192.168.1.44:8090/</u:URLBase>
  <u:device>
    <u:deviceType>urn:schemas-upnp-org:device:MediaServer:1</u:deviceType>
    <u:friendlyName>Not a renderer</u:friendlyName>
  </u:device>
  <u:device>
    <u:deviceType>urn:schemas-upnp-org:device:MediaRenderer:2</u:deviceType>
    <u:friendlyName>Weird Renderer</u:friendlyName>
    <u:manufacturer>Acme</u:manufacturer>
    <u:modelName>X1</u:modelName>
    <u:UDN>uuid:aaaa-bbbb</u:UDN>
    <u:serviceList>
      <u:service>
        <u:serviceType>urn:schemas-upnp-org:service:AVTransport:2</u:serviceType>
        <u:controlURL>ctl/AVT</u:controlURL>
      </u:service>
    </u:serviceList>
  </u:device>
</u:root>
""".trimIndent()

val xxeDescription = """
<?xml version="1.0"?>
<!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>&xxe;</friendlyName>
    <UDN>uuid:evil</UDN>
    <serviceList><service>
      <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
      <controlURL>/x</controlURL>
    </service></serviceList>
  </device>
</root>
""".trimIndent()

fun rendererWithSink(sink: String, manufacturer: String = "Denon", model: String = "AVR-X2800H"): DlnaRenderer {
    val base = DlnaDeviceParser.parse("http://192.168.1.50:8080/desc.xml", denonDescription.toByteArray())!!
    return base.copy(
        manufacturer = manufacturer,
        modelName = model,
        quirks = DlnaQuirkTable.forDevice(manufacturer, model, base.friendlyName),
        sinkProtocolInfo = DlnaProtocolInfo.parseList(sink)
    )
}

fun main() {
    println("== protocolInfo parsing ==")
    val lpcm = DlnaProtocolInfo.parse("http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01")!!
    eq("protocol", lpcm.protocol, "http-get")
    eq("mimeBase keeps only media type", lpcm.mimeBase, "audio/l16")
    eq("profile extracted", lpcm.profileName, "LPCM")
    check("not wildcard", !lpcm.isWildcard)

    val wildcard = DlnaProtocolInfo.parse("http-get:*:*:*")!!
    check("wildcard detected", wildcard.isWildcard)

    val noExtra = DlnaProtocolInfo.parse("http-get:*:audio/mpeg")!!
    eq("missing 4th field tolerated", noExtra.mimeBase, "audio/mpeg")
    eq("no profile", noExtra.profileName, null)

    check("garbage rejected", DlnaProtocolInfo.parse("nonsense") == null)

    val sink = "rtsp-rtp-udp:*:audio/L16:*," +
        "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3," +
        "http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM," +
        "http-get:*:audio/x-flac:*"
    val parsed = DlnaProtocolInfo.parseList(sink)
    eq("only http-get entries kept", parsed.size, 3)

    println("== device description ==")
    val denon = DlnaDeviceParser.parse("http://192.168.1.50:8080/desc.xml", denonDescription.toByteArray())!!
    eq("friendly name", denon.friendlyName, "Denon AVR-X2800H")
    eq("udn without uuid prefix", denon.udn, "5f9ec1b3-ed59-79bb-4530-0005cdaf1234")
    eq("absolute control url", denon.avTransport.controlUrl, "http://192.168.1.50:8080/upnp/control/AVTransport1")
    eq("service type preserved", denon.avTransport.serviceType, "urn:schemas-upnp-org:service:AVTransport:1")
    check("connection manager found", denon.connectionManager != null)
    check("rendering control found", denon.renderingControl != null)
    eq("host extracted", denon.address, "192.168.1.50")

    val weird = DlnaDeviceParser.parse("http://192.168.1.44:8090/dd.xml", prefixedDescription.toByteArray())!!
    eq("namespace prefixes ignored", weird.friendlyName, "Weird Renderer")
    eq("skips MediaServer device", weird.modelName, "X1")
    eq("AVTransport:2 accepted", weird.avTransport.serviceType, "urn:schemas-upnp-org:service:AVTransport:2")
    eq("relative url resolved against URLBase", weird.avTransport.controlUrl, "http://192.168.1.44:8090/ctl/AVT")

    val evil = DlnaDeviceParser.parse("http://10.0.0.1/x.xml", xxeDescription.toByteArray())
    check("XXE payload not expanded", evil == null || !(evil.friendlyName.contains("root:")),
        "friendlyName=${evil?.friendlyName}")

    println("== quirks ==")
    eq("denon matches heos rule", DlnaQuirkTable.forDevice("Denon", "AVR-X2800H", "").name, "heos")
    eq("heos play delay", DlnaQuirkTable.forDevice("Denon", "AVR-X2800H", "").playDelayMs, 600L)
    eq("sonos rule", DlnaQuirkTable.forDevice("Sonos, Inc.", "One SL", "").preferredOrder.first(), DlnaCodec.MP3)
    eq("unknown device is generic", DlnaQuirkTable.forDevice("Acme", "X1", "").name, "generic")
    eq("generic prefers lpcm", DlnaQuirkTable.forDevice("Acme", "X1", "").preferredOrder.first(), DlnaCodec.LPCM)

    println("== negotiation ==")
    val all = setOf(DlnaCodec.LPCM, DlnaCodec.WAV, DlnaCodec.MP3, DlnaCodec.ADTS)

    val full = rendererWithSink(sink)
    val autoPick = DlnaNegotiator.negotiate(full, all, DlnaFormatPreference.AUTO, 44100, 2)!!
    eq("auto picks lpcm when offered", autoPick.codec, DlnaCodec.LPCM)
    check("marked as negotiated", autoPick.negotiated)
    eq("lpcm mime is rebuilt with our rate", autoPick.mime, "audio/L16;rate=44100;channels=2")

    val mp3Only = rendererWithSink("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3")
    val mp3Pick = DlnaNegotiator.negotiate(mp3Only, all, DlnaFormatPreference.AUTO, 44100, 2)!!
    eq("falls to mp3 when only mp3 offered", mp3Pick.codec, DlnaCodec.MP3)
    eq("mp3 mime", mp3Pick.mime, "audio/mpeg")
    eq("mp3 profile", mp3Pick.profileName, "MP3")

    val wavOnly = rendererWithSink("http-get:*:audio/x-wav:DLNA.ORG_PN=WAV")
    val wavPick = DlnaNegotiator.negotiate(wavOnly, all, DlnaFormatPreference.AUTO, 48000, 2)!!
    eq("wav alias matched", wavPick.codec, DlnaCodec.WAV)
    eq("wav mime taken from renderer", wavPick.mime, "audio/x-wav")

    val emptySink = rendererWithSink("")
    val blindPick = DlnaNegotiator.negotiate(emptySink, all, DlnaFormatPreference.AUTO, 44100, 2)!!
    eq("blind fallback is lpcm", blindPick.codec, DlnaCodec.LPCM)
    check("blind fallback not flagged negotiated", !blindPick.negotiated)

    val wildcardSink = rendererWithSink("http-get:*:*:*")
    val wildPick = DlnaNegotiator.negotiate(wildcardSink, all, DlnaFormatPreference.AUTO, 44100, 2)!!
    eq("wildcard sink uses preferred order", wildPick.codec, DlnaCodec.LPCM)

    val forced = DlnaNegotiator.negotiate(full, all, DlnaFormatPreference.MP3, 44100, 2)!!
    eq("manual override honoured", forced.codec, DlnaCodec.MP3)
    check("manual override not flagged negotiated", !forced.negotiated)

    check("forced codec unavailable returns null",
        DlnaNegotiator.negotiate(full, setOf(DlnaCodec.LPCM), DlnaFormatPreference.MP3, 44100, 2) == null)

    val sonos = rendererWithSink(sink, "Sonos, Inc.", "One SL")
    val sonosPick = DlnaNegotiator.negotiate(sonos, all, DlnaFormatPreference.AUTO, 44100, 2)!!
    eq("sonos quirk reorders to mp3", sonosPick.codec, DlnaCodec.MP3)

    val aacOnly = rendererWithSink("http-get:*:audio/vnd.dlna.adts:DLNA.ORG_PN=AAC_ADTS_320")
    eq("adts matched by profile prefix",
        DlnaNegotiator.negotiate(aacOnly, all, DlnaFormatPreference.AUTO, 44100, 2)!!.codec, DlnaCodec.ADTS)

    println("== DIDL ==")
    val didl = DlnaDidl.build(full, autoPick, "http://192.168.1.9:8081/dlna/lpcm.raw?a=1&b=2", "Marco & co <PC>")
    check("didl escapes ampersand in url", didl.contains("a=1&amp;b=2"), didl)
    check("didl escapes title", didl.contains("Marco &amp; co &lt;PC&gt;"))
    check("didl has protocolInfo with flags", didl.contains("DLNA.ORG_FLAGS=8D500000000000000000000000000000"))
    check("didl declares broadcast class", didl.contains("object.item.audioItem.audioBroadcast"))
    check("didl is parseable xml", DlnaXml.parse(didl.toByteArray()) != null)
    check("didl res element carries url", didl.contains("dlna/lpcm.raw"))

    println("== SSDP ==")
    val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.50:8080/desc.xml\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:abc::urn:x\r\nSERVER: Linux UPnP/1.0 Denon\r\n\r\n"
    val headers = DlnaSsdp.parseHeaders(response)
    eq("location lowercased key", headers["location"], "http://192.168.1.50:8080/desc.xml")
    eq("st parsed", headers["st"], "urn:schemas-upnp-org:device:MediaRenderer:1")
    eq("server parsed", headers["server"], "Linux UPnP/1.0 Denon")

    println("== selection persistence ==")
    val encoded = DlnaSelection.encode(denon.udn, denon.displayName)
    eq("round trip udn", DlnaSelection.udnOf(encoded), denon.udn)
    eq("round trip name", DlnaSelection.nameOf(encoded), "Denon AVR-X2800H")
    val toggledOn = DlnaSelection.toggle(emptyList(), denon)
    check("toggle adds", DlnaSelection.contains(toggledOn, denon.udn))
    check("toggle removes", !DlnaSelection.contains(DlnaSelection.toggle(toggledOn, denon), denon.udn))
    eq("legacy entry without name still resolves", DlnaSelection.nameOf("bare-udn"), "bare-udn")
    eq("udns set", DlnaSelection.udns(listOf(encoded, "x|Y")), setOf(denon.udn, "x"))

    protocolStatusChecks()
    wfasPairingUriChecks()
    snapcastProtocolChecks()

    println()
    if (failures == 0) println("ALL CHECKS PASSED") else println("$failures CHECK(S) FAILED")
    if (failures > 0) exitProcess(1)
}

fun protocolStatusChecks() {
    println("== protocol summary ==")
    eq("all four", ProtocolStatus.summary(true, true, true, true, "and"), "WFAS, RTP, HTTP and DLNA")
    eq("wfas off", ProtocolStatus.summary(false, true, true, true, "and"), "RTP, HTTP and DLNA")
    eq("two", ProtocolStatus.summary(false, true, true, false, "and"), "RTP and HTTP")
    eq("only dlna", ProtocolStatus.summary(false, false, false, true, "and"), "DLNA")
    eq("none", ProtocolStatus.summary(false, false, false, false, "and"), "")
    eq("italian conjunction", ProtocolStatus.summary(false, false, true, true, "e"), "HTTP e DLNA")
}
