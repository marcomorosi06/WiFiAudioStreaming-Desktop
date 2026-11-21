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
 *
 * ---------------------------------------------------------------------------
 * wfas3_opus.c - Opus for WFAS v3 streams (specification 8.3), outside the
 * core, as the specification requires.
 *
 * Every packet is exactly payload_bytes long: hard CBR, application
 * RESTRICTED_LOWDELAY, no DTX, no in-band FEC, and a packet the encoder made
 * shorter is padded with opus_packet_pad (RFC 6716 padding, discarded by
 * every decoder). Constant packet size is what keeps the content of the audio
 * from showing in the length of the packets.
 *
 * The decoder only ever sees packets that the core has already authenticated.
 * ------------------------------------------------------------------------- */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "opus.h"
#include "wfas3_glue.h"

#ifndef WFAS3_OPUS_CLASS
#error "WFAS3_OPUS_CLASS must name the Java class of the Opus natives"
#endif

#define W3O_MAGIC_ENC 0x4F505545U
#define W3O_MAGIC_DEC 0x4F505544U
#define W3O_MAX_PAYLOAD 1344
#define W3O_MAX_SAMPLES 960       /* 20 ms at 48 kHz, per channel */
#define W3O_MAX_CHANNELS 2

typedef struct w3o {
    uint32_t     magic;
    int          channels;
    int          frame;
    int          payload;
    OpusEncoder *enc;
    OpusDecoder *dec;
} w3o;

static int w3o_frame_ok(int frame)
{
    return (frame == 120) || (frame == 240) || (frame == 480) || (frame == 960);
}

static w3o *w3o_get(jlong h, uint32_t magic)
{
    w3o *o = (w3o *)(uintptr_t)h;

    return ((o != NULL) && (o->magic == magic)) ? o : NULL;
}

/* Encoder for 48 kHz audio: frame samples per channel, payload bytes per
 * packet. The bitrate follows from them. 0 if the combination cannot work. */
static jlong JNICALL o_enc_create(JNIEnv *env, jclass cls, jint channels, jint frame, jint payload,
                                  jint complexity)
{
    w3o *o;
    int err = 0;
    long bitrate;

    (void)env;
    (void)cls;
    if ((channels < 1) || (channels > W3O_MAX_CHANNELS) || (w3o_frame_ok(frame) == 0) ||
        (payload < 1) || (payload > W3O_MAX_PAYLOAD) || (complexity < 0) || (complexity > 10)) {
        return 0;
    }
    bitrate = ((long)payload * 8L * 48000L) / (long)frame;
    if ((bitrate < 6000L) || (bitrate > 510000L)) {
        return 0;
    }
    o = (w3o *)calloc(1U, sizeof *o);
    if (o == NULL) {
        return 0;
    }
    o->enc = opus_encoder_create(48000, channels, OPUS_APPLICATION_RESTRICTED_LOWDELAY, &err);
    if ((o->enc == NULL) || (err != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_VBR(0)) != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_BITRATE((opus_int32)bitrate)) != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_DTX(0)) != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_INBAND_FEC(0)) != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_PACKET_LOSS_PERC(0)) != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK) ||
        (opus_encoder_ctl(o->enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC)) != OPUS_OK)) {
        if (o->enc != NULL) {
            opus_encoder_destroy(o->enc);
        }
        free(o);
        return 0;
    }
    o->channels = channels;
    o->frame = frame;
    o->payload = payload;
    o->magic = W3O_MAGIC_ENC;
    return (jlong)(uintptr_t)o;
}

/* One frame of interleaved 16-bit PCM into exactly payload bytes. Returns
 * the payload length, or a negative value on error (nothing to send). */
static jint JNICALL o_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jbyteArray out)
{
    w3o *o = w3o_get(h, W3O_MAGIC_ENC);
    opus_int16 in[W3O_MAX_SAMPLES * W3O_MAX_CHANNELS];
    unsigned char pkt[W3O_MAX_PAYLOAD];
    int n;
    jsize need;

    (void)cls;
    if (o == NULL) {
        return -1;
    }
    need = (jsize)(o->frame * o->channels);
    if ((pcm == NULL) || (out == NULL) || ((*env)->GetArrayLength(env, pcm) < need) ||
        ((*env)->GetArrayLength(env, out) < (jsize)o->payload)) {
        return -1;
    }
    (*env)->GetShortArrayRegion(env, pcm, 0, need, (jshort *)in);
    if ((*env)->ExceptionCheck(env)) {
        return -1;
    }
    n = opus_encode(o->enc, in, o->frame, pkt, (opus_int32)o->payload);
    if ((n > 0) && (n < o->payload)) {
        n = (opus_packet_pad(pkt, (opus_int32)n, (opus_int32)o->payload) == OPUS_OK) ? o->payload : -1;
    }
    if (n != o->payload) {
        w3_wipe(pkt, sizeof pkt);
        w3_wipe(in, sizeof in);
        return -1;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)pkt);
    w3_wipe(pkt, sizeof pkt);
    w3_wipe(in, sizeof in);
    return (jint)n;
}

static void JNICALL o_enc_destroy(JNIEnv *env, jclass cls, jlong h)
{
    w3o *o = w3o_get(h, W3O_MAGIC_ENC);

    (void)env;
    (void)cls;
    if (o != NULL) {
        o->magic = 0U;
        opus_encoder_destroy(o->enc);
        free(o);
    }
}

static jlong JNICALL o_dec_create(JNIEnv *env, jclass cls, jint channels, jint frame)
{
    w3o *o;
    int err = 0;

    (void)env;
    (void)cls;
    if ((channels < 1) || (channels > W3O_MAX_CHANNELS) || (w3o_frame_ok(frame) == 0)) {
        return 0;
    }
    o = (w3o *)calloc(1U, sizeof *o);
    if (o == NULL) {
        return 0;
    }
    o->dec = opus_decoder_create(48000, channels, &err);
    if ((o->dec == NULL) || (err != OPUS_OK)) {
        if (o->dec != NULL) {
            opus_decoder_destroy(o->dec);
        }
        free(o);
        return 0;
    }
    o->channels = channels;
    o->frame = frame;
    o->magic = W3O_MAGIC_DEC;
    return (jlong)(uintptr_t)o;
}

/* One packet (data != null) or one frame of concealment (data == null) into
 * pcm. Returns samples per channel, or a negative value on error. */
static jint JNICALL o_decode(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jint len, jshortArray pcm)
{
    w3o *o = w3o_get(h, W3O_MAGIC_DEC);
    unsigned char pkt[W3O_MAX_PAYLOAD];
    opus_int16 outp[W3O_MAX_SAMPLES * W3O_MAX_CHANNELS];
    int n;

    (void)cls;
    if ((o == NULL) || (pcm == NULL) || ((*env)->GetArrayLength(env, pcm) < (jsize)(o->frame * o->channels))) {
        return -1;
    }
    if (data != NULL) {
        if ((len <= 0) || (len > W3O_MAX_PAYLOAD) || ((*env)->GetArrayLength(env, data) < len)) {
            return -1;
        }
        (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)pkt);
        if ((*env)->ExceptionCheck(env)) {
            return -1;
        }
        n = opus_decode(o->dec, pkt, (opus_int32)len, outp, o->frame, 0);
    } else {
        n = opus_decode(o->dec, NULL, 0, outp, o->frame, 0);
    }
    if ((n > 0) && (n <= o->frame)) {
        (*env)->SetShortArrayRegion(env, pcm, 0, (jsize)(n * o->channels), (const jshort *)outp);
    } else {
        n = -1;
    }
    w3_wipe(pkt, sizeof pkt);
    w3_wipe(outp, sizeof outp);
    return (jint)n;
}

static void JNICALL o_dec_destroy(JNIEnv *env, jclass cls, jlong h)
{
    w3o *o = w3o_get(h, W3O_MAGIC_DEC);

    (void)env;
    (void)cls;
    if (o != NULL) {
        o->magic = 0U;
        opus_decoder_destroy(o->dec);
        free(o);
    }
}

static const JNINativeMethod w3o_methods[] = {
    { "nEncCreate", "(IIII)J", (void *)o_enc_create },
    { "nEncode", "(J[S[B)I", (void *)o_encode },
    { "nEncDestroy", "(J)V", (void *)o_enc_destroy },
    { "nDecCreate", "(II)J", (void *)o_dec_create },
    { "nDecode", "(J[BI[S)I", (void *)o_decode },
    { "nDecDestroy", "(J)V", (void *)o_dec_destroy },
};

int w3_opus_register(JNIEnv *env)
{
    jclass cls = (*env)->FindClass(env, WFAS3_OPUS_CLASS);

    if (cls == NULL) {
        return -1;
    }
    return ((*env)->RegisterNatives(env, cls, w3o_methods,
                                    (jint)(sizeof w3o_methods / sizeof w3o_methods[0])) == 0) ? 0 : -1;
}
