/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

package wfas3

/**
 * Natives of libwfas3 (glue/wfas3_jni.c). Bound by RegisterNatives in
 * JNI_OnLoad: the library exports nothing else. Every call is made by
 * [Wfas3Engine] under its lock.
 */
internal object Wfas3Native {
    @JvmStatic external fun nSelftest(): Int
    @JvmStatic external fun nClockUs(): Long
    @JvmStatic external fun nLayout(what: Int): Int
    @JvmStatic external fun nCreate(record: ByteArray?, recOut: ByteArray): Long
    @JvmStatic external fun nDestroy(h: Long)
    @JvmStatic external fun nNextDeadline(h: Long): Long
    @JvmStatic external fun nSetPolicy(h: Long, busy: Int, codecMask: Int, maxChannels: Int, suggest: IntArray): Int
    @JvmStatic external fun nDeviceExport(h: Long, out: ByteArray): Int
    @JvmStatic external fun nPairImport(h: Long, slot: Int, record: ByteArray): Int
    @JvmStatic external fun nPairExport(h: Long, slot: Int, out: ByteArray): Int
    @JvmStatic external fun nPairInfo(h: Long, slot: Int, out: ByteArray): Int
    @JvmStatic external fun nPairRemove(h: Long, slot: Int, out: ByteArray): Int
    @JvmStatic external fun nFind(h: Long, slot: Int, on: Int, out: ByteArray): Int
    @JvmStatic external fun nConnect(h: Long, slot: Int, ip: ByteArray, port: Int, cfg: IntArray, out: ByteArray): Int
    @JvmStatic external fun nPoll(h: Long, out: ByteArray): Int
    @JvmStatic external fun nReceive(h: Long, data: ByteArray, len: Int, ip: ByteArray, port: Int, out: ByteArray): Int
    @JvmStatic external fun nSendAudio(h: Long, session: Int, pos: Long, capUs: Long, data: ByteArray, off: Int, len: Int, out: ByteArray): Int
    @JvmStatic external fun nGroupSend(h: Long, pos: Long, capUs: Long, data: ByteArray, off: Int, len: Int, out: ByteArray): Int
    @JvmStatic external fun nStreamWant(h: Long, session: Int, want: Int): Int
    @JvmStatic external fun nStreamParams(h: Long, session: Int, cfg: IntArray): Int
    @JvmStatic external fun nMicOff(h: Long, session: Int): Int
    @JvmStatic external fun nAudioLate(h: Long, session: Int, lateUs: Long): Int
    @JvmStatic external fun nSetBufferMs(h: Long, session: Int, ms: Int): Int
    @JvmStatic external fun nSessionClose(h: Long, session: Int, reason: Int, out: ByteArray): Int
    @JvmStatic external fun nSessionInfo(h: Long, session: Int, out: LongArray): Int
    @JvmStatic external fun nWindowOpen(h: Long, kind: Int, name: ByteArray, ask: Int, perms: Int, texts: Array<String?>, out: ByteArray): Int
    @JvmStatic external fun nWindowClose(h: Long, out: ByteArray): Int
    @JvmStatic external fun nPairAnswer(h: Long, ask: Int, yes: Int, perms: Int, guest: Int, out: ByteArray): Int
    @JvmStatic external fun nWindowFailed(h: Long): Int
    @JvmStatic external fun nBeacons(h: Long, on: Int): Int
    @JvmStatic external fun nCodeValid(text: String): Boolean
    @JvmStatic external fun nPairStart(h: Long, kind: Int, code: String?, pin: ByteArray?, beacon: ByteArray?, name: ByteArray, flags: Int, perms: Int, replace: Int, out: ByteArray): Int
    @JvmStatic external fun nPairConfirm(h: Long, yes: Int, out: ByteArray): Int
    @JvmStatic external fun nPairCancel(h: Long): Int
    @JvmStatic external fun nNameClean(raw: ByteArray): String?
    @JvmStatic external fun nGroupOpen(h: Long, cfg: IntArray, ip: ByteArray, port: Int): Int
    @JvmStatic external fun nGroupClose(h: Long): Int
    @JvmStatic external fun nGroupMembers(h: Long): Int
}

/** Opus natives (glue/wfas3_opus.c): 48 kHz, hard CBR, exact packet length. */
internal object Wfas3Opus {
    @JvmStatic external fun nEncCreate(channels: Int, frame: Int, payload: Int, complexity: Int): Long
    @JvmStatic external fun nEncode(h: Long, pcm: ShortArray, out: ByteArray): Int
    @JvmStatic external fun nEncDestroy(h: Long)
    @JvmStatic external fun nDecCreate(channels: Int, frame: Int): Long
    @JvmStatic external fun nDecode(h: Long, data: ByteArray?, len: Int, pcm: ShortArray): Int
    @JvmStatic external fun nDecDestroy(h: Long)
}
