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
 * audio_engine_mac.m
 *
 * macOS implementation of the audio capture engine for WiFi Audio Streaming.
 * Uses ScreenCaptureKit (available from macOS 12.3+) to capture system audio
 * without installing virtual drivers like BlackHole.
 *
 * ScreenCaptureKit is not available as a pure C API: it requires Objective-C
 * (or Swift). This .m file is compiled with clang as Objective-C and linked
 * together with audio_engine.c into the same shared library.
 *
 * Data Flow:
 * SCStream -> delegate callback -> lock-free ring buffer -> mac_engine_read()
 *
 * Compatibility:
 * - macOS 13+ (Ventura): Stable ScreenCaptureKit
 * - macOS 12.3+: ScreenCaptureKit available but API slightly different
 * - macOS < 12.3: fallback to CoreAudio aggregate device (BlackHole only)
 */

#import <Foundation/Foundation.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreAudio/CoreAudio.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>

/* ─────────────────────────────────────────────────────────────────────────────
 * Ring buffer per il trasferimento lock-free tra callback e thread di lettura.
 * Capacità = 4 secondi di audio stereo 48kHz int16 = 4 * 48000 * 2 * 2 = 768 KB
 * ───────────────────────────────────────────────────────────────────────────*/

#define RING_CAPACITY (1 << 20)   /* 1 MB, potenza di 2 per la maschera */
#define RING_MASK     (RING_CAPACITY - 1)

typedef struct {
    int16_t  buf[RING_CAPACITY / sizeof(int16_t)];
    volatile uint64_t write_pos;
    volatile uint64_t read_pos;
} RingBuffer;

static RingBuffer *g_ring = NULL;

static void ring_write(const int16_t *src, size_t n_shorts) {
    if (!g_ring) return;
    for (size_t i = 0; i < n_shorts; i++) {
        uint64_t wp = g_ring->write_pos;
        size_t idx  = (size_t)(wp & (RING_CAPACITY / sizeof(int16_t) - 1));
        g_ring->buf[idx] = src[i];
        __atomic_store_n(&g_ring->write_pos, wp + 1, __ATOMIC_RELEASE);
    }
}

static int ring_read(int16_t *dst, size_t n_shorts) {
    if (!g_ring) return 0;
    uint64_t rp = g_ring->read_pos;
    uint64_t wp = __atomic_load_n(&g_ring->write_pos, __ATOMIC_ACQUIRE);
    uint64_t available = wp - rp;
    if (available < n_shorts) return 0;

    size_t cap_shorts = RING_CAPACITY / sizeof(int16_t);
    for (size_t i = 0; i < n_shorts; i++) {
        dst[i] = g_ring->buf[(rp + i) & (cap_shorts - 1)];
    }
    __atomic_store_n(&g_ring->read_pos, rp + n_shorts, __ATOMIC_RELEASE);
    return 1;
}

/* ─────────────────────────────────────────────────────────────────────────────
 * Stato globale macOS
 * ───────────────────────────────────────────────────────────────────────────*/

static char       g_mac_error[512]  = {0};
static int        g_mac_initialized = 0;
static int        g_mac_channels    = 2;
static int        g_mac_sample_rate = 48000;

/* SCStream e delegate sono oggetti ObjC — li teniamo come puntatori void
 * per evitare di includere ScreenCaptureKit.h nel file C principale. */
static __strong id g_stream   = nil;
static __strong id g_delegate = nil;

/* Semaforo per attendere che SCShareableContent sia disponibile (async API) */
static dispatch_semaphore_t g_sema = NULL;
static int g_sema_result = 0;

static void mac_set_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_mac_error, sizeof(g_mac_error), fmt, ap);
    va_end(ap);
    fprintf(stderr, "[AudioEngine/macOS] %s\n", g_mac_error);
}

/* ─────────────────────────────────────────────────────────────────────────────
 * Delegate di SCStream
 * ───────────────────────────────────────────────────────────────────────────*/

API_AVAILABLE(macos(13.0))
@interface WFASStreamDelegate : NSObject <SCStreamOutput, SCStreamDelegate>
@property (nonatomic, assign) int targetChannels;
@property (nonatomic, assign) int targetSampleRate;
@end

@implementation WFASStreamDelegate

- (void)stream:(SCStream *)stream
    didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
                   ofType:(SCStreamOutputType)type {

    if (type != SCStreamOutputTypeAudio) return;
    if (!CMSampleBufferIsValid(sampleBuffer)) return;

    CMFormatDescriptionRef desc = CMSampleBufferGetFormatDescription(sampleBuffer);
    const AudioStreamBasicDescription *asbd =
        CMAudioFormatDescriptionGetStreamBasicDescription(desc);
    if (!asbd) return;

    CMItemCount num_frames = CMSampleBufferGetNumSamples(sampleBuffer);
    if (num_frames <= 0) return;

    int is_float   = (asbd->mFormatFlags & kAudioFormatFlagIsFloat) != 0;
    int is_non_int = (asbd->mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0;
    int src_ch     = (int)asbd->mChannelsPerFrame;
    int bits       = (int)asbd->mBitsPerChannel;
    if (src_ch <= 0) return;

    size_t ablSize = 0;
    CMBlockBufferRef bbRetained = NULL;
    OSStatus st = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
        sampleBuffer,
        &ablSize, NULL, 0,
        kCFAllocatorDefault, kCFAllocatorDefault,
        kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
        &bbRetained);
    if (st != noErr || ablSize == 0) {
        if (bbRetained) CFRelease(bbRetained);
        return;
    }

    AudioBufferList *abl = (AudioBufferList *)malloc(ablSize);
    if (!abl) {
        if (bbRetained) CFRelease(bbRetained);
        return;
    }

    if (bbRetained) { CFRelease(bbRetained); bbRetained = NULL; }

    st = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
        sampleBuffer,
        &ablSize, abl, ablSize,
        kCFAllocatorDefault, kCFAllocatorDefault,
        kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
        &bbRetained);

    if (st != noErr || abl->mNumberBuffers == 0) {
        free(abl);
        if (bbRetained) CFRelease(bbRetained);
        return;
    }

    int16_t *tmp = (int16_t *)malloc((size_t)num_frames * 2 * sizeof(int16_t));
    if (!tmp) {
        free(abl);
        if (bbRetained) CFRelease(bbRetained);
        return;
    }

    size_t emitted = 0;

    if (is_float && is_non_int) {
        float *ch0 = (float *)abl->mBuffers[0].mData;
        float *ch1 = (abl->mNumberBuffers >= 2)
                     ? (float *)abl->mBuffers[1].mData
                     : ch0;
        size_t ch0_bytes = abl->mBuffers[0].mDataByteSize;
        size_t frames_abl = ch0_bytes / sizeof(float);
        if (frames_abl > (size_t)num_frames) frames_abl = (size_t)num_frames;
        if (ch0) {
            for (size_t f = 0; f < frames_abl; f++) {
                float lf = ch0[f];
                float rf = ch1 ? ch1[f] : lf;
                int32_t li = (int32_t)(lf * 32768.0f);
                int32_t ri = (int32_t)(rf * 32768.0f);
                if (li >  32767) li =  32767; if (li < -32768) li = -32768;
                if (ri >  32767) ri =  32767; if (ri < -32768) ri = -32768;
                tmp[f * 2]     = (int16_t)li;
                tmp[f * 2 + 1] = (int16_t)ri;
            }
            emitted = frames_abl;
        }
    } else if (is_float && !is_non_int) {
        float *fp = (float *)abl->mBuffers[0].mData;
        size_t bytes = abl->mBuffers[0].mDataByteSize;
        size_t frames_abl = bytes / (sizeof(float) * (size_t)src_ch);
        if (frames_abl > (size_t)num_frames) frames_abl = (size_t)num_frames;
        if (fp) {
            for (size_t f = 0; f < frames_abl; f++) {
                float lf = fp[f * src_ch];
                float rf = (src_ch >= 2) ? fp[f * src_ch + 1] : lf;
                int32_t li = (int32_t)(lf * 32768.0f);
                int32_t ri = (int32_t)(rf * 32768.0f);
                if (li >  32767) li =  32767; if (li < -32768) li = -32768;
                if (ri >  32767) ri =  32767; if (ri < -32768) ri = -32768;
                tmp[f * 2]     = (int16_t)li;
                tmp[f * 2 + 1] = (int16_t)ri;
            }
            emitted = frames_abl;
        }
    } else if (!is_float && bits == 16 && !is_non_int) {
        int16_t *sp = (int16_t *)abl->mBuffers[0].mData;
        size_t bytes = abl->mBuffers[0].mDataByteSize;
        size_t frames_abl = bytes / (sizeof(int16_t) * (size_t)src_ch);
        if (frames_abl > (size_t)num_frames) frames_abl = (size_t)num_frames;
        if (sp) {
            for (size_t f = 0; f < frames_abl; f++) {
                tmp[f * 2]     = sp[f * src_ch];
                tmp[f * 2 + 1] = (src_ch >= 2) ? sp[f * src_ch + 1] : sp[f * src_ch];
            }
            emitted = frames_abl;
        }
    } else if (!is_float && bits == 16 && is_non_int) {
        int16_t *c0 = (int16_t *)abl->mBuffers[0].mData;
        int16_t *c1 = (abl->mNumberBuffers >= 2)
                      ? (int16_t *)abl->mBuffers[1].mData
                      : c0;
        size_t bytes = abl->mBuffers[0].mDataByteSize;
        size_t frames_abl = bytes / sizeof(int16_t);
        if (frames_abl > (size_t)num_frames) frames_abl = (size_t)num_frames;
        if (c0) {
            for (size_t f = 0; f < frames_abl; f++) {
                tmp[f * 2]     = c0[f];
                tmp[f * 2 + 1] = c1 ? c1[f] : c0[f];
            }
            emitted = frames_abl;
        }
    }

    if (emitted > 0) ring_write(tmp, emitted * 2);

    free(tmp);
    free(abl);
    if (bbRetained) CFRelease(bbRetained);
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    if (error) {
        mac_set_error("SCStream stopped: %s",
                      [[error localizedDescription] UTF8String]);
    }
}

@end

/* ─────────────────────────────────────────────────────────────────────────────
 * Funzioni pubbliche chiamate da audio_engine.c
 * ───────────────────────────────────────────────────────────────────────────*/

jboolean mac_engine_start(int sample_rate, int channels, int buffer_frames) {
    if (@available(macOS 13.0, *)) {

        if (g_mac_initialized) mac_engine_stop();

        g_mac_channels    = channels;
        g_mac_sample_rate = sample_rate;
        g_mac_error[0]    = '\0';

        g_ring = (RingBuffer *)calloc(1, sizeof(RingBuffer));
        if (!g_ring) {
            mac_set_error("Allocazione ring buffer fallita");
            return JNI_FALSE;
        }

        g_sema = dispatch_semaphore_create(0);
        __block int start_ok = 0;

        [SCShareableContent getShareableContentWithCompletionHandler:
            ^(SCShareableContent *content, NSError *error) {
                if (error || !content) {
                    mac_set_error("getShareableContent error: %s",
                                  error ? [[error localizedDescription] UTF8String]
                                        : "content is nil");
                    dispatch_semaphore_signal(g_sema);
                    return;
                }

                /*
                 * Filtro: cattura tutto il display (audio incluso),
                 * escludendo la nostra stessa finestra per evitare feedback.
                 * Per loopback puro (solo audio) possiamo escludere tutte le
                 * finestre e tenere solo il display — SCKit catturerà comunque
                 * l'audio di sistema.
                 */
                SCContentFilter *filter;
                SCDisplay *display = content.displays.firstObject;
                if (!display) {
                    mac_set_error("Nessun display trovato");
                    dispatch_semaphore_signal(g_sema);
                    return;
                }

                filter = [[SCContentFilter alloc]
                    initWithDisplay:display
                    excludingWindows:@[]];

                SCStreamConfiguration *cfg = [[SCStreamConfiguration alloc] init];
                cfg.capturesAudio           = YES;
                cfg.excludesCurrentProcessAudio = YES;  /* niente feedback */
                cfg.sampleRate              = sample_rate;
                cfg.channelCount            = channels;

                /* Impostiamo dimensione frame in base al buffer_frames richiesto */
                if (buffer_frames > 0) {
                    /*
                     * minimumFrameInterval determina con quale frequenza arrivano
                     * le callback. CMTime con valore 1/sample_rate * buffer_frames.
                     */
                    cfg.minimumFrameInterval = CMTimeMake(buffer_frames, sample_rate);
                }

                /* Non ci interessa il video: impostiamo risoluzione minima */
                cfg.width  = 2;
                cfg.height = 2;

                WFASStreamDelegate *del = [[WFASStreamDelegate alloc] init];
                del.targetChannels   = channels;
                del.targetSampleRate = sample_rate;
                g_delegate = del;

                SCStream *stream = [[SCStream alloc]
                    initWithFilter:filter
                    configuration:cfg
                    delegate:del];
                g_stream = stream;

                NSError *addErr = nil;
                BOOL added = [stream addStreamOutput:del
                                                type:SCStreamOutputTypeAudio
                                   sampleHandlerQueue:dispatch_get_global_queue(
                                       QOS_CLASS_USER_INTERACTIVE, 0)
                                               error:&addErr];
                if (!added) {
                    mac_set_error("addStreamOutput error: %s",
                                  [[addErr localizedDescription] UTF8String]);
                    dispatch_semaphore_signal(g_sema);
                    return;
                }

                [stream startCaptureWithCompletionHandler:^(NSError *startErr) {
                    if (startErr) {
                        mac_set_error("startCapture error: %s",
                                      [[startErr localizedDescription] UTF8String]);
                        start_ok = 0;
                    } else {
                        printf("[AudioEngine/macOS] SCStream started. %d ch, %d Hz\n",
                               channels, sample_rate);
                        start_ok = 1;
                    }
                    dispatch_semaphore_signal(g_sema);
                }];
            }
        ];

        /* Attendi al massimo 5 secondi */
        dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW,
                                                (int64_t)(5 * NSEC_PER_SEC));
        long rc = dispatch_semaphore_wait(g_sema, timeout);
        if (rc != 0) {
            mac_set_error("Timeout in attesa di SCShareableContent");
            return JNI_FALSE;
        }

        if (!start_ok) return JNI_FALSE;

        g_mac_initialized = 1;
        return JNI_TRUE;

    } else {
        mac_set_error("La cattura audio via ScreenCaptureKit richiede macOS 13.0 o superiore. "
                      "Aggiorna macOS oppure disattiva il motore nativo nelle impostazioni.");
        return JNI_FALSE;
    }
}

jint mac_engine_read(int16_t *out_buf, int num_stereo_samples) {
    if (!g_mac_initialized || !g_ring) return 0;

    /*
     * Aspettiamo che il ring buffer abbia abbastanza dati.
     * Tentiamo 50 volte con 1ms di sleep tra i tentativi (= 50ms max wait).
     * Se SCStream è in silenzio, forniamo zeri per non bloccare il thread Kotlin.
     */
    for (int attempt = 0; attempt < 50; attempt++) {
        if (ring_read(out_buf, (size_t)(num_stereo_samples * 2))) {
            return (jint)(num_stereo_samples * 2);
        }
        struct timespec ts = {0, 1000000L}; /* 1 ms */
        nanosleep(&ts, NULL);
    }

    /* silenzio — non è un errore */
    memset(out_buf, 0, (size_t)(num_stereo_samples * 2) * sizeof(int16_t));
    return (jint)(num_stereo_samples * 2);
}

void mac_engine_stop(void) {
    if (!g_mac_initialized) return;
    g_mac_initialized = 0;

    if (g_stream) {
        if (@available(macOS 13.0, *)) {
            [(SCStream *)g_stream stopCaptureWithCompletionHandler:^(NSError *e) {
                if (e) fprintf(stderr, "[AudioEngine/macOS] stopCapture: %s\n",
                               [[e localizedDescription] UTF8String]);
            }];
        }
        g_stream = nil;
    }
    g_delegate = nil;

    if (g_ring) {
        free(g_ring);
        g_ring = NULL;
    }

    g_sema = NULL;

    printf("[AudioEngine/macOS] Stopped.\n");
}

void mac_engine_get_error(char *buf, int buf_size) {
    strncpy(buf, g_mac_error, (size_t)buf_size - 1);
    buf[buf_size - 1] = '\0';
}
