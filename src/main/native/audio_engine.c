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
 * audio_engine.c
 *
 * Native audio capture engine for WiFi Audio Streaming Desktop.
 * Implements audio loopback (capturing what is being played) via:
 * - Windows : WASAPI Loopback (no virtual driver required)
 * - Linux   : PulseAudio/PipeWire simple API (default sink monitor)
 * - macOS   : stub — delegated to audio_engine_mac.m (ScreenCaptureKit)
 *
 * JNI Interface exposed to Kotlin (package com.marcomorosi.wfas):
 *
 * boolean AudioEngine_nativeStart(int sampleRate, int channels, int bufferFrames)
 * boolean AudioEngine_nativeRead(short[] outBuf, int numSamples)
 * void    AudioEngine_nativeStop()
 * String  AudioEngine_nativeGetError()
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <pthread.h>
#include <unistd.h>
#endif

/* ─────────────────────────────────────────────────────────────────────────────
 * Stato condiviso (thread-safe: scritto da start/stop, letto da read)
 * ───────────────────────────────────────────────────────────────────────────*/

static char g_last_error[512] = {0};

static void set_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, ap);
    va_end(ap);
    fprintf(stderr, "[AudioEngine] %s\n", g_last_error);
}

/* ─────────────────────────────────────────────────────────────────────────────
 * Mic mixing ring buffer (platform-agnostic). Scritto dal thread di rete,
 * letto dal thread di cattura/invio audio. I campioni sono int16 interleaved.
 * ───────────────────────────────────────────────────────────────────────────*/

#define MIC_RING_CAP_SAMPLES (48000 * 2 * 2)
static int16_t g_mic_ring[MIC_RING_CAP_SAMPLES];
static volatile int   g_mic_ring_w  = 0;
static volatile int   g_mic_ring_r  = 0;
static volatile int   g_mic_enabled = 0;
static volatile float g_mic_volume  = 1.0f;

#if defined(_WIN32)
static SRWLOCK g_mic_srw = SRWLOCK_INIT;
static void mic_lock(void)   { AcquireSRWLockExclusive(&g_mic_srw); }
static void mic_unlock(void) { ReleaseSRWLockExclusive(&g_mic_srw); }
#else
static pthread_mutex_t g_mic_mx = PTHREAD_MUTEX_INITIALIZER;
static void mic_lock(void)   { pthread_mutex_lock(&g_mic_mx); }
static void mic_unlock(void) { pthread_mutex_unlock(&g_mic_mx); }
#endif

static void mic_ring_reset(void) {
    mic_lock();
    g_mic_ring_w = 0;
    g_mic_ring_r = 0;
    mic_unlock();
}

static void mic_ring_push(const int16_t *samples, int count) {
    if (count <= 0 || !samples) return;
    mic_lock();
    int w = g_mic_ring_w;
    int r = g_mic_ring_r;
    for (int i = 0; i < count; i++) {
        g_mic_ring[w] = samples[i];
        w = (w + 1) % MIC_RING_CAP_SAMPLES;
        if (w == r) r = (r + 1) % MIC_RING_CAP_SAMPLES;
    }
    g_mic_ring_w = w;
    g_mic_ring_r = r;
    mic_unlock();
}

static int mic_ring_pop(int16_t *out, int count) {
    mic_lock();
    int w = g_mic_ring_w;
    int r = g_mic_ring_r;
    int available = (w - r + MIC_RING_CAP_SAMPLES) % MIC_RING_CAP_SAMPLES;
    int to_copy = (count < available) ? count : available;
    for (int i = 0; i < to_copy; i++) {
        out[i] = g_mic_ring[r];
        r = (r + 1) % MIC_RING_CAP_SAMPLES;
    }
    g_mic_ring_r = r;
    mic_unlock();
    return to_copy;
}

static void mic_mix_into(int16_t *out, int num_shorts) {
    if (!g_mic_enabled || num_shorts <= 0 || !out) return;
    int16_t stack_buf[4096];
    int16_t *buf = stack_buf;
    int16_t *heap = NULL;
    const int stack_cap = (int)(sizeof(stack_buf) / sizeof(int16_t));
    if (num_shorts > stack_cap) {
        heap = (int16_t *)malloc((size_t)num_shorts * sizeof(int16_t));
        if (!heap) return;
        buf = heap;
    }
    int got = mic_ring_pop(buf, num_shorts);
    if (got > 0) {
        float vol = g_mic_volume;
        for (int i = 0; i < got; i++) {
            int32_t m = (int32_t)((float)buf[i] * vol);
            int32_t s = (int32_t)out[i] + m;
            if (s >  32767) s =  32767;
            if (s < -32768) s = -32768;
            out[i] = (int16_t)s;
        }
    }
    if (heap) free(heap);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  WINDOWS — WASAPI Loopback
 * ═══════════════════════════════════════════════════════════════════════════*/
#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#define COBJMACROS
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <endpointvolume.h>
#include <mmreg.h>
#include <ksmedia.h>
#include <propidl.h>
#include <functiondiscoverykeys_devpkey.h>
#include <stdarg.h>

static const CLSID CLSID_MMDeviceEnumerator_local =
    {0xBCDE0395,0xE52F,0x467C,{0x8E,0x3D,0xC4,0x57,0x92,0x91,0x69,0x2E}};
static const IID IID_IMMDeviceEnumerator_local =
    {0xA95664D2,0x9614,0x4F35,{0xA7,0x46,0xDE,0x8D,0xB6,0x36,0x17,0xE6}};
static const IID IID_IAudioClient_local =
    {0x1CB9AD4C,0xDBFA,0x4c32,{0xB1,0x78,0xC2,0xF5,0x68,0xA7,0x03,0xB2}};
static const IID IID_IAudioCaptureClient_local =
    {0xC8ADBD64,0xE71E,0x48a0,{0xA4,0xDE,0x18,0x5C,0x39,0x5C,0xD3,0x17}};
static const IID IID_IAudioEndpointVolume_local =
    {0x5CDF2C82,0x841E,0x4546,{0x97,0x22,0x0C,0xF7,0x40,0x78,0x22,0x9A}};

static IMMDeviceEnumerator  *g_enumerator    = NULL;
static IMMDevice            *g_device        = NULL;
static IAudioClient         *g_audio_client  = NULL;
static IAudioCaptureClient  *g_capture_client = NULL;
static WAVEFORMATEX         *g_mix_format    = NULL;

static int                   g_initialized   = 0;
static int                   g_channels_req  = 2;
static int                   g_rate_req      = 48000;
static int                   g_src_is_float  = 0;
static int                   g_src_channels  = 2;
static int                   g_src_rate      = 48000;
static int                   g_src_bps       = 16;

static volatile int          g_stopping      = 0;
static volatile int          g_reading       = 0;

static float                 g_resample_phase[8] = {0};
static int16_t               g_prev_sample[8] = {0};
static double                g_resample_pos  = 0.0;

static int16_t*              g_src_accum = NULL;
static int                   g_src_accum_cap = 0;
static int                   g_src_accum_len = 0;

static IAudioEndpointVolume *g_endpoint_volume = NULL;
static BOOL                  g_prev_mute_state = FALSE;
static int                   g_had_prev_mute_state = 0;
static int                   g_did_mute_endpoint = 0;
static volatile LONG         g_atexit_registered = 0;
static volatile LONG         g_safety_net_ran = 0;

static int16_t float_to_s16(float f) {
    int32_t v = (int32_t)(f * 32768.0f);
    if (v >  32767) v =  32767;
    if (v < -32768) v = -32768;
    return (int16_t)v;
}

static void wasapi_stop(void);

static int is_virtual_device(const wchar_t *name) {
    static const wchar_t *keywords[] = {
        L"CABLE", L"VB-Audio", L"VB-CABLE", L"Voicemeeter",
        L"BlackHole", L"Virtual", L"Null", NULL
    };
    if (!name) return 0;
    for (int i = 0; keywords[i]; i++) {
        if (wcsstr(name, keywords[i])) return 1;
    }
    return 0;
}

static IMMDevice *pick_best_render_device(IMMDeviceEnumerator *enumerator) {
    IMMDevice *default_dev    = NULL;
    IMMDevice *best_real_dev  = NULL;

    IMMDeviceEnumerator_GetDefaultAudioEndpoint(enumerator, eRender, eConsole, &default_dev);

    wchar_t *default_id = NULL;
    if (default_dev) IMMDevice_GetId(default_dev, &default_id);

    IMMDeviceCollection *collection = NULL;
    HRESULT hr = IMMDeviceEnumerator_EnumAudioEndpoints(
        enumerator, eRender, DEVICE_STATE_ACTIVE, &collection);
    if (FAILED(hr) || !collection) {
        if (default_id) CoTaskMemFree(default_id);
        return default_dev;
    }

    UINT count = 0;
    IMMDeviceCollection_GetCount(collection, &count);

    int default_is_virtual = 0;

    for (UINT i = 0; i < count; i++) {
        IMMDevice *dev = NULL;
        if (FAILED(IMMDeviceCollection_Item(collection, i, &dev)) || !dev) continue;

        IPropertyStore *props = NULL;
        PROPVARIANT pv;
        PropVariantInit(&pv);
        const wchar_t *name = NULL;

        if (SUCCEEDED(IMMDevice_OpenPropertyStore(dev, STGM_READ, &props)) && props) {
            if (SUCCEEDED(IPropertyStore_GetValue(props, &PKEY_Device_FriendlyName, &pv))
                && pv.vt == VT_LPWSTR) {
                name = pv.pwszVal;
            }
        }

        int is_virt = is_virtual_device(name);

        wchar_t *dev_id = NULL;
        IMMDevice_GetId(dev, &dev_id);
        int is_default = (default_id && dev_id && wcscmp(default_id, dev_id) == 0);
        if (dev_id) CoTaskMemFree(dev_id);

        fprintf(stderr, "[AudioEngine] Device[%u]: %S virtual=%d default=%d\n",
                i, name ? name : L"(unknown)", is_virt, is_default);

        PropVariantClear(&pv);
        if (props) IUnknown_Release((IUnknown *)props);

        if (is_default && is_virt) default_is_virtual = 1;

        if (!is_virt) {
            if (is_default) {
                if (best_real_dev) IUnknown_Release((IUnknown *)best_real_dev);
                best_real_dev = dev;
            } else if (!best_real_dev) {
                best_real_dev = dev;
            } else {
                IUnknown_Release((IUnknown *)dev);
            }
        } else {
            IUnknown_Release((IUnknown *)dev);
        }
    }

    IUnknown_Release((IUnknown *)collection);
    if (default_id) CoTaskMemFree(default_id);

    if (best_real_dev) {
        if (default_dev) IUnknown_Release((IUnknown *)default_dev);
        fprintf(stderr, "[AudioEngine] Selected: real device for loopback.\n");
        return best_real_dev;
    }

    fprintf(stderr, "[AudioEngine] Selected: %s default device.\n",
            default_is_virtual ? "virtual default (no real device found)" : "default");
    return default_dev;
}

static void safety_net_restore_mute(void) {
    if (InterlockedCompareExchange(&g_safety_net_ran, 1, 0) != 0) return;

    IAudioEndpointVolume *epv = g_endpoint_volume;
    if (!epv) return;

    if (g_did_mute_endpoint) {
        BOOL target = g_had_prev_mute_state ? g_prev_mute_state : FALSE;
        HRESULT hr = IAudioEndpointVolume_SetMute(epv, target, NULL);
        if (SUCCEEDED(hr)) {
            fprintf(stderr, "[AudioEngine] Safety-net restored endpoint mute to %s.\n",
                    target ? "muted" : "unmuted");
        } else {
            fprintf(stderr, "[AudioEngine] Safety-net SetMute failed: 0x%08lX\n", hr);
        }
    }
}

static void atexit_safety_handler(void) {
    safety_net_restore_mute();
    if (g_endpoint_volume) {
        IUnknown_Release((IUnknown *)g_endpoint_volume);
        g_endpoint_volume = NULL;
    }
}

static void mute_render_endpoint(IMMDevice *device) {
    if (!device) return;

    HRESULT hr = IMMDevice_Activate(
        device,
        &IID_IAudioEndpointVolume_local,
        CLSCTX_ALL,
        NULL,
        (void **)&g_endpoint_volume);

    if (FAILED(hr) || !g_endpoint_volume) {
        fprintf(stderr, "[AudioEngine] Cannot activate IAudioEndpointVolume: 0x%08lX\n", hr);
        g_endpoint_volume = NULL;
        return;
    }

    BOOL prev = FALSE;
    hr = IAudioEndpointVolume_GetMute(g_endpoint_volume, &prev);
    if (SUCCEEDED(hr)) {
        g_prev_mute_state = prev;
        g_had_prev_mute_state = 1;
    } else {
        g_had_prev_mute_state = 0;
    }

    if (g_had_prev_mute_state && prev) {
        fprintf(stderr, "[AudioEngine] Endpoint already muted, leaving as-is.\n");
        g_did_mute_endpoint = 0;
        return;
    }

    hr = IAudioEndpointVolume_SetMute(g_endpoint_volume, TRUE, NULL);
    if (SUCCEEDED(hr)) {
        g_did_mute_endpoint = 1;
        g_safety_net_ran = 0;
        fprintf(stderr, "[AudioEngine] Render endpoint muted to avoid double playback.\n");

        if (InterlockedCompareExchange(&g_atexit_registered, 1, 0) == 0) {
            atexit(atexit_safety_handler);
        }
    } else {
        fprintf(stderr, "[AudioEngine] SetMute(TRUE) failed: 0x%08lX\n", hr);
        g_did_mute_endpoint = 0;
    }
}

static void restore_render_endpoint_mute(void) {
    if (!g_endpoint_volume) return;

    if (g_did_mute_endpoint && g_had_prev_mute_state) {
        HRESULT hr = IAudioEndpointVolume_SetMute(g_endpoint_volume, g_prev_mute_state, NULL);
        if (SUCCEEDED(hr)) {
            fprintf(stderr, "[AudioEngine] Endpoint mute restored to %s.\n",
                    g_prev_mute_state ? "muted" : "unmuted");
        } else {
            fprintf(stderr, "[AudioEngine] SetMute restore failed: 0x%08lX\n", hr);
        }
    }

    InterlockedExchange(&g_safety_net_ran, 1);

    IUnknown_Release((IUnknown *)g_endpoint_volume);
    g_endpoint_volume = NULL;
    g_did_mute_endpoint = 0;
    g_had_prev_mute_state = 0;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
    (void)hinst;
    (void)reserved;
    if (reason == DLL_PROCESS_DETACH) {
        safety_net_restore_mute();
    }
    return TRUE;
}

static jboolean wasapi_start(int sample_rate, int channels) {
    if (g_initialized) {
        wasapi_stop();
    }

    g_stopping = 0;
    HRESULT hr;

    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) return JNI_FALSE;

    hr = CoCreateInstance(&CLSID_MMDeviceEnumerator_local, NULL, CLSCTX_ALL,
                          &IID_IMMDeviceEnumerator_local, (void **)&g_enumerator);
    if (FAILED(hr)) { set_error("CoCreateInstance enumerator failed: 0x%08lX", hr); return JNI_FALSE; }

    g_device = pick_best_render_device(g_enumerator);
    if (!g_device) { set_error("No render device found"); return JNI_FALSE; }

    mute_render_endpoint(g_device);

    hr = IMMDevice_Activate(g_device, &IID_IAudioClient_local, CLSCTX_ALL, NULL, (void **)&g_audio_client);
    if (FAILED(hr)) { set_error("IMMDevice_Activate failed: 0x%08lX", hr); return JNI_FALSE; }

    hr = IAudioClient_GetMixFormat(g_audio_client, &g_mix_format);
    if (FAILED(hr)) { set_error("GetMixFormat failed: 0x%08lX", hr); return JNI_FALSE; }

    if (g_mix_format->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
        WAVEFORMATEXTENSIBLE *ext = (WAVEFORMATEXTENSIBLE *)g_mix_format;
        g_src_is_float = IsEqualGUID(&ext->SubFormat, &KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
    } else {
        g_src_is_float = (g_mix_format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT);
    }

    g_src_channels = g_mix_format->nChannels;
    g_src_rate     = (int)g_mix_format->nSamplesPerSec;
    g_src_bps      = g_mix_format->wBitsPerSample;

    fprintf(stderr, "[AudioEngine/WASAPI] Loopback on device: %d ch, %d Hz, %d bps, float=%d\n",
            g_src_channels, g_src_rate, g_src_bps, g_src_is_float);

    hr = IAudioClient_Initialize(g_audio_client, AUDCLNT_SHAREMODE_SHARED,
                                 AUDCLNT_STREAMFLAGS_LOOPBACK, 2000000, 0, g_mix_format, NULL);
    if (FAILED(hr)) { set_error("IAudioClient_Initialize failed: 0x%08lX", hr); return JNI_FALSE; }

    hr = IAudioClient_GetService(g_audio_client, &IID_IAudioCaptureClient_local, (void **)&g_capture_client);
    if (FAILED(hr)) { set_error("GetService CaptureClient failed: 0x%08lX", hr); return JNI_FALSE; }

    hr = IAudioClient_Start(g_audio_client);
    if (FAILED(hr)) { set_error("IAudioClient_Start failed: 0x%08lX", hr); return JNI_FALSE; }

    g_channels_req = channels;
    g_rate_req     = sample_rate;
    g_resample_pos = 0.0;
    for (int i = 0; i < 8; i++) g_prev_sample[i] = 0;
    g_src_accum_len = 0;

    g_initialized = 1;
    return JNI_TRUE;
}

static int16_t fetch_frame_ch(BYTE *pData, UINT32 frame_idx, int ch, DWORD flags) {
    if (!pData || (flags & AUDCLNT_BUFFERFLAGS_SILENT)) return 0;
    int src_ch = (ch < g_src_channels) ? ch : (g_src_channels - 1);

    if (g_src_is_float) {
        float *fp = (float *)pData + frame_idx * g_src_channels;
        return float_to_s16(fp[src_ch]);
    } else {
        if (g_src_bps == 16) {
            int16_t *sp = (int16_t *)pData + frame_idx * g_src_channels;
            return sp[src_ch];
        } else if (g_src_bps == 32) {
            int32_t *sp = (int32_t *)pData + frame_idx * g_src_channels;
            int32_t s = sp[src_ch];
            return (int16_t)((s < 0 ? -((-s) >> 16) : (s >> 16)));
        } else if (g_src_bps == 24) {
            BYTE *p = pData + (frame_idx * g_src_channels + src_ch) * 3;
            int32_t v = (p[0] << 8) | (p[1] << 16) | (p[2] << 24);
            return (int16_t)(v >> 16);
        }
    }
    return 0;
}

static jint wasapi_read(int16_t *out, int num_stereo_samples) {
    g_reading = 1;
#if defined(_MSC_VER)
    MemoryBarrier();
#else
    __sync_synchronize();
#endif
    if (!g_initialized || g_stopping || !g_capture_client) {
        g_reading = 0;
        return 0;
    }

    HRESULT hr;
    int written = 0;
    int tgt_ch = g_channels_req;
    double ratio = (double)g_src_rate / (double)g_rate_req;
    int need_resample = (g_src_rate != g_rate_req);

    int wait_count = 0;
    const int max_wait_ms = g_mic_enabled ? 20 : 2000;

    while (written < num_stereo_samples && !g_stopping) {
        UINT32 packet_size = 0;
        hr = IAudioCaptureClient_GetNextPacketSize(g_capture_client, &packet_size);
        if (FAILED(hr)) break;

        if (packet_size == 0) {
            if (wait_count >= max_wait_ms) break;
            Sleep(1);
            wait_count++;
            continue;
        }
        wait_count = 0;

        BYTE  *pData       = NULL;
        UINT32 frames_available = 0;
        DWORD  flags       = 0;

        hr = IAudioCaptureClient_GetBuffer(g_capture_client, &pData, &frames_available, &flags, NULL, NULL);
        if (FAILED(hr)) break;

        if (!need_resample) {
            UINT32 f;
            for (f = 0; f < frames_available && written < num_stereo_samples; f++) {
                int16_t l = fetch_frame_ch(pData, f, 0, flags);
                int16_t r = (tgt_ch >= 2) ? fetch_frame_ch(pData, f, 1, flags) : l;
                out[written * 2]     = l;
                out[written * 2 + 1] = (tgt_ch >= 2) ? r : l;
                written++;
            }
        } else {
            int needed = g_src_accum_len + (int)frames_available;
            if (needed > g_src_accum_cap) {
                int new_cap = g_src_accum_cap > 0 ? g_src_accum_cap : 4096;
                while (new_cap < needed) new_cap *= 2;
                int16_t *new_buf = (int16_t *)realloc(g_src_accum, (size_t)new_cap * 2 * sizeof(int16_t));
                if (!new_buf) {
                    IAudioCaptureClient_ReleaseBuffer(g_capture_client, frames_available);
                    break;
                }
                g_src_accum = new_buf;
                g_src_accum_cap = new_cap;
            }

            for (UINT32 f = 0; f < frames_available; f++) {
                int16_t l = fetch_frame_ch(pData, f, 0, flags);
                int16_t r = (tgt_ch >= 2) ? fetch_frame_ch(pData, f, 1, flags) : l;
                g_src_accum[(g_src_accum_len + (int)f) * 2]     = l;
                g_src_accum[(g_src_accum_len + (int)f) * 2 + 1] = r;
            }
            g_src_accum_len += (int)frames_available;

            while (written < num_stereo_samples) {
                double idx_d = g_resample_pos;
                int idx = (int)idx_d;
                if (idx + 1 >= g_src_accum_len) break;

                double frac = idx_d - (double)idx;
                int16_t l0 = g_src_accum[idx * 2];
                int16_t r0 = g_src_accum[idx * 2 + 1];
                int16_t l1 = g_src_accum[(idx + 1) * 2];
                int16_t r1 = g_src_accum[(idx + 1) * 2 + 1];

                double lf = (double)l0 * (1.0 - frac) + (double)l1 * frac;
                double rf = (double)r0 * (1.0 - frac) + (double)r1 * frac;
                if (lf >  32767.0) lf =  32767.0; if (lf < -32768.0) lf = -32768.0;
                if (rf >  32767.0) rf =  32767.0; if (rf < -32768.0) rf = -32768.0;
                int16_t l = (int16_t)lf;
                int16_t r = (int16_t)rf;

                out[written * 2]     = l;
                out[written * 2 + 1] = r;
                written++;

                g_resample_pos += ratio;
            }

            int consumed = (int)g_resample_pos;
            if (consumed > 0 && consumed <= g_src_accum_len) {
                int remaining = g_src_accum_len - consumed;
                if (remaining > 0) {
                    memmove(g_src_accum, g_src_accum + consumed * 2, (size_t)remaining * 2 * sizeof(int16_t));
                }
                g_src_accum_len = remaining;
                g_resample_pos -= (double)consumed;
                if (g_resample_pos < 0.0) g_resample_pos = 0.0;
            }
        }

        IAudioCaptureClient_ReleaseBuffer(g_capture_client, frames_available);
    }

    g_reading = 0;
    return (jint)written;
}

static void wasapi_stop(void) {
    if (!g_initialized) return;

    g_stopping = 1;
    while (g_reading) { Sleep(1); }

    g_initialized = 0;
    if (g_audio_client)   IAudioClient_Stop(g_audio_client);
    while (g_reading) { Sleep(1); }
    IAudioCaptureClient *cap = g_capture_client; g_capture_client = NULL;
    if (cap) IUnknown_Release((IUnknown *)cap);
    if (g_mix_format)     { CoTaskMemFree(g_mix_format); g_mix_format = NULL; }
    if (g_audio_client)   { IUnknown_Release((IUnknown *)g_audio_client); g_audio_client = NULL; }
    restore_render_endpoint_mute();
    if (g_device)         { IUnknown_Release((IUnknown *)g_device); g_device = NULL; }
    if (g_enumerator)     { IUnknown_Release((IUnknown *)g_enumerator); g_enumerator = NULL; }
    CoUninitialize();

    g_stopping = 0;
}

#ifndef AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
#define AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM 0x80000000
#endif
#ifndef AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
#define AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY 0x08000000
#endif

static const IID IID_IAudioRenderClient_local =
    {0xF294ACFC,0x3146,0x4483,{0xA7,0xBF,0xAD,0xDC,0xA7,0xC2,0x60,0xE2}};

#define WS_RING_CAP (48000 * 2 * 4)

typedef struct {
    IMMDeviceEnumerator *enumerator;
    IMMDevice           *device;
    IAudioClient        *client;
    IAudioRenderClient  *render;
    HANDLE               event;
    HANDLE               worker;
    UINT32               buffer_frames;
    int                  sample_rate;
    int                  channels;
    int16_t             *ring;
    int                  ring_cap;
    volatile int         ring_w;
    volatile int         ring_r;
    SRWLOCK              ring_lock;
    volatile LONG        running;
    char                 device_name[256];
} wasapi_sink_t;

static wasapi_sink_t g_ws = {0};
static volatile LONG g_ws_com_inited = 0;

static int wasapi_sink_push(const int16_t *samples, int count) {
    if (!g_ws.ring || count <= 0) return 0;
    AcquireSRWLockExclusive(&g_ws.ring_lock);
    int w = g_ws.ring_w;
    int r = g_ws.ring_r;
    int pushed = 0;
    for (int i = 0; i < count; i++) {
        g_ws.ring[w] = samples[i];
        w = (w + 1) % g_ws.ring_cap;
        if (w == r) r = (r + 1) % g_ws.ring_cap;
        pushed++;
    }
    g_ws.ring_w = w;
    g_ws.ring_r = r;
    ReleaseSRWLockExclusive(&g_ws.ring_lock);
    return pushed;
}

static int wasapi_sink_pop(int16_t *out, int count) {
    if (!g_ws.ring || count <= 0) return 0;
    AcquireSRWLockExclusive(&g_ws.ring_lock);
    int w = g_ws.ring_w;
    int r = g_ws.ring_r;
    int avail = (w - r + g_ws.ring_cap) % g_ws.ring_cap;
    int n = (count < avail) ? count : avail;
    for (int i = 0; i < n; i++) {
        out[i] = g_ws.ring[r];
        r = (r + 1) % g_ws.ring_cap;
    }
    g_ws.ring_r = r;
    ReleaseSRWLockExclusive(&g_ws.ring_lock);
    return n;
}

static IMMDevice *wasapi_sink_find_device(IMMDeviceEnumerator *enumerator, const wchar_t *name_hint) {
    static const wchar_t *auto_keywords[] = {
        L"CABLE Input", L"CABLE-A Input", L"CABLE-B Input",
        L"VB-Audio", L"VoiceMeeter Input", L"Virtual",
        NULL
    };

    IMMDeviceCollection *collection = NULL;
    HRESULT hr = IMMDeviceEnumerator_EnumAudioEndpoints(
        enumerator, eRender, DEVICE_STATE_ACTIVE, &collection);
    if (FAILED(hr) || !collection) return NULL;

    UINT count = 0;
    IMMDeviceCollection_GetCount(collection, &count);

    IMMDevice *found = NULL;
    IMMDevice *auto_pick = NULL;

    for (UINT i = 0; i < count; i++) {
        IMMDevice *dev = NULL;
        if (FAILED(IMMDeviceCollection_Item(collection, i, &dev)) || !dev) continue;

        IPropertyStore *props = NULL;
        PROPVARIANT pv;
        PropVariantInit(&pv);
        const wchar_t *fname = NULL;

        if (SUCCEEDED(IMMDevice_OpenPropertyStore(dev, STGM_READ, &props)) && props) {
            if (SUCCEEDED(IPropertyStore_GetValue(props, &PKEY_Device_FriendlyName, &pv)) && pv.vt == VT_LPWSTR) {
                fname = pv.pwszVal;
            }
        }

        int matched = 0;
        if (name_hint && name_hint[0] != L'\0' && fname) {
            if (wcsstr(fname, name_hint) != NULL) matched = 1;
        }

        int auto_match = 0;
        if (!matched && !auto_pick && fname) {
            for (int k = 0; auto_keywords[k]; k++) {
                if (wcsstr(fname, auto_keywords[k])) { auto_match = 1; break; }
            }
        }

        PropVariantClear(&pv);
        if (props) IUnknown_Release((IUnknown *)props);

        if (matched) {
            if (found) IUnknown_Release((IUnknown *)found);
            found = dev;
            break;
        } else if (auto_match) {
            auto_pick = dev;
        } else {
            IUnknown_Release((IUnknown *)dev);
        }
    }

    IUnknown_Release((IUnknown *)collection);

    if (found) {
        if (auto_pick) IUnknown_Release((IUnknown *)auto_pick);
        return found;
    }
    return auto_pick;
}

static DWORD WINAPI wasapi_sink_worker(LPVOID ptr) {
    (void)ptr;
    HRESULT hr_ci = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    while (InterlockedOr(&g_ws.running, 0)) {
        DWORD wr = WaitForSingleObject(g_ws.event, 200);
        if (wr != WAIT_OBJECT_0) continue;
        if (!g_ws.client || !g_ws.render) continue;

        UINT32 padding = 0;
        if (FAILED(IAudioClient_GetCurrentPadding(g_ws.client, &padding))) continue;
        UINT32 avail_frames = (g_ws.buffer_frames > padding) ? (g_ws.buffer_frames - padding) : 0;
        if (avail_frames == 0) continue;

        BYTE *data = NULL;
        if (FAILED(IAudioRenderClient_GetBuffer(g_ws.render, avail_frames, &data)) || !data) continue;

        int need_shorts = (int)avail_frames * g_ws.channels;
        int16_t stack_buf[8192];
        int16_t *buf = stack_buf;
        int16_t *heap = NULL;
        if (need_shorts > (int)(sizeof(stack_buf) / sizeof(int16_t))) {
            heap = (int16_t *)malloc((size_t)need_shorts * sizeof(int16_t));
            if (!heap) {
                IAudioRenderClient_ReleaseBuffer(g_ws.render, avail_frames, AUDCLNT_BUFFERFLAGS_SILENT);
                continue;
            }
            buf = heap;
        }

        int got = wasapi_sink_pop(buf, need_shorts);
        if (got < need_shorts) {
            memset(buf + got, 0, (size_t)(need_shorts - got) * sizeof(int16_t));
        }
        memcpy(data, buf, (size_t)need_shorts * sizeof(int16_t));

        IAudioRenderClient_ReleaseBuffer(g_ws.render, avail_frames, 0);
        if (heap) free(heap);
    }
    if (SUCCEEDED(hr_ci)) CoUninitialize();
    return 0;
}

static void wasapi_sink_close_internal(void) {
    InterlockedExchange(&g_ws.running, 0);
    if (g_ws.event) SetEvent(g_ws.event);
    if (g_ws.worker) {
        WaitForSingleObject(g_ws.worker, 2000);
        CloseHandle(g_ws.worker);
        g_ws.worker = NULL;
    }
    if (g_ws.client)    { IAudioClient_Stop(g_ws.client); }
    if (g_ws.render)    { IUnknown_Release((IUnknown *)g_ws.render);     g_ws.render = NULL; }
    if (g_ws.client)    { IUnknown_Release((IUnknown *)g_ws.client);     g_ws.client = NULL; }
    if (g_ws.device)    { IUnknown_Release((IUnknown *)g_ws.device);     g_ws.device = NULL; }
    if (g_ws.enumerator){ IUnknown_Release((IUnknown *)g_ws.enumerator); g_ws.enumerator = NULL; }
    if (g_ws.event)     { CloseHandle(g_ws.event); g_ws.event = NULL; }
    if (g_ws.ring)      { free(g_ws.ring); g_ws.ring = NULL; }
    g_ws.ring_cap = 0;
    g_ws.ring_w = 0;
    g_ws.ring_r = 0;
    g_ws.buffer_frames = 0;
    g_ws.device_name[0] = '\0';
    if (InterlockedExchange(&g_ws_com_inited, 0) == 1) {
        CoUninitialize();
    }
}

static jboolean wasapi_sink_open(const wchar_t *device_hint, int sample_rate, int channels) {
    wasapi_sink_close_internal();

    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) {
        set_error("CoInitializeEx failed: 0x%08lX", hr);
        return JNI_FALSE;
    }
    if (hr == S_OK || hr == S_FALSE) InterlockedExchange(&g_ws_com_inited, 1);

    InitializeSRWLock(&g_ws.ring_lock);

    hr = CoCreateInstance(&CLSID_MMDeviceEnumerator_local, NULL, CLSCTX_ALL,
                          &IID_IMMDeviceEnumerator_local, (void **)&g_ws.enumerator);
    if (FAILED(hr) || !g_ws.enumerator) {
        set_error("CoCreateInstance(MMDeviceEnumerator) failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    g_ws.device = wasapi_sink_find_device(g_ws.enumerator, device_hint);
    if (!g_ws.device) {
        set_error("Nessun dispositivo render compatibile trovato (cerca 'CABLE Input' o simile).");
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    {
        IPropertyStore *props = NULL;
        PROPVARIANT pv; PropVariantInit(&pv);
        if (SUCCEEDED(IMMDevice_OpenPropertyStore(g_ws.device, STGM_READ, &props)) && props) {
            if (SUCCEEDED(IPropertyStore_GetValue(props, &PKEY_Device_FriendlyName, &pv)) && pv.vt == VT_LPWSTR) {
                WideCharToMultiByte(CP_UTF8, 0, pv.pwszVal, -1, g_ws.device_name, sizeof(g_ws.device_name), NULL, NULL);
            }
            PropVariantClear(&pv);
            IUnknown_Release((IUnknown *)props);
        }
    }

    hr = IMMDevice_Activate(g_ws.device, &IID_IAudioClient_local, CLSCTX_ALL, NULL, (void **)&g_ws.client);
    if (FAILED(hr) || !g_ws.client) {
        set_error("IMMDevice_Activate(IAudioClient) failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    WAVEFORMATEX wfx;
    memset(&wfx, 0, sizeof(wfx));
    wfx.wFormatTag      = WAVE_FORMAT_PCM;
    wfx.nChannels       = (WORD)channels;
    wfx.nSamplesPerSec  = (DWORD)sample_rate;
    wfx.wBitsPerSample  = 16;
    wfx.nBlockAlign     = (WORD)(wfx.nChannels * wfx.wBitsPerSample / 8);
    wfx.nAvgBytesPerSec = wfx.nSamplesPerSec * wfx.nBlockAlign;
    wfx.cbSize          = 0;

    DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    REFERENCE_TIME hns_buffer = 200000;

    hr = IAudioClient_Initialize(g_ws.client, AUDCLNT_SHAREMODE_SHARED, flags,
                                  hns_buffer, 0, &wfx, NULL);
    if (FAILED(hr)) {
        set_error("IAudioClient_Initialize failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    g_ws.event = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (!g_ws.event) {
        set_error("CreateEvent failed");
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    hr = IAudioClient_SetEventHandle(g_ws.client, g_ws.event);
    if (FAILED(hr)) {
        set_error("IAudioClient_SetEventHandle failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    hr = IAudioClient_GetBufferSize(g_ws.client, &g_ws.buffer_frames);
    if (FAILED(hr)) {
        set_error("IAudioClient_GetBufferSize failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    hr = IAudioClient_GetService(g_ws.client, &IID_IAudioRenderClient_local, (void **)&g_ws.render);
    if (FAILED(hr) || !g_ws.render) {
        set_error("IAudioClient_GetService(IAudioRenderClient) failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    g_ws.sample_rate = sample_rate;
    g_ws.channels    = channels;
    g_ws.ring_cap    = WS_RING_CAP;
    g_ws.ring        = (int16_t *)calloc((size_t)g_ws.ring_cap, sizeof(int16_t));
    if (!g_ws.ring) {
        set_error("malloc ring failed");
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }
    g_ws.ring_w = 0;
    g_ws.ring_r = 0;

    {
        BYTE *data = NULL;
        if (SUCCEEDED(IAudioRenderClient_GetBuffer(g_ws.render, g_ws.buffer_frames, &data)) && data) {
            memset(data, 0, (size_t)g_ws.buffer_frames * wfx.nBlockAlign);
            IAudioRenderClient_ReleaseBuffer(g_ws.render, g_ws.buffer_frames, 0);
        }
    }

    hr = IAudioClient_Start(g_ws.client);
    if (FAILED(hr)) {
        set_error("IAudioClient_Start failed: 0x%08lX", hr);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    InterlockedExchange(&g_ws.running, 1);
    g_ws.worker = CreateThread(NULL, 0, wasapi_sink_worker, NULL, 0, NULL);
    if (!g_ws.worker) {
        set_error("CreateThread worker failed");
        InterlockedExchange(&g_ws.running, 0);
        wasapi_sink_close_internal();
        return JNI_FALSE;
    }

    fprintf(stderr, "[AudioEngine] WASAPI sink aperto su '%s' (%dHz/%dch, bufferFrames=%u)\n",
            g_ws.device_name, sample_rate, channels, g_ws.buffer_frames);

    return JNI_TRUE;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  LINUX — PulseAudio / PipeWire (interfaccia libpulse-simple compatibile)
 * ═══════════════════════════════════════════════════════════════════════════*/
#elif defined(__linux__)

#include <stdarg.h>
#include <pulse/simple.h>
#include <pulse/error.h>

static pa_simple *g_pa_stream = NULL;
static int        g_pa_initialized = 0;

/*
 * Su Linux leggiamo dal "monitor" del sink di default.
 * Il nome del monitor è sempre "<nome_sink>.monitor".
 * Se passiamo NULL come device, PulseAudio/PipeWire scelgono
 * automaticamente il monitor del sink di default — più robusto.
 */
static char* get_default_monitor(void) {
    static char monitor_name[256];
    FILE *fp = popen("pactl get-default-sink 2>/dev/null", "r");

    if (!fp) return NULL;

    char sink[128] = {0};
    if (fgets(sink, sizeof(sink), fp)) {
        sink[strcspn(sink, "\r\n")] = 0;
        if (strlen(sink) > 0) {
            snprintf(monitor_name, sizeof(monitor_name), "%s.monitor", sink);
            pclose(fp);
            return monitor_name;
        }
    }
    pclose(fp);
    return NULL;
}

static jboolean pulse_start(int sample_rate, int channels) {
    pa_sample_spec ss;
    ss.format   = PA_SAMPLE_S16LE;
    ss.rate     = (uint32_t)sample_rate;
    ss.channels = (uint8_t)channels;

    uint32_t bytes_per_sec = sample_rate * channels * 2;
    uint32_t target_latency_bytes = (bytes_per_sec * 10) / 1000;

    pa_buffer_attr attr;
    attr.maxlength = target_latency_bytes * 4;
    attr.tlength   = (uint32_t)-1;
    attr.prebuf    = (uint32_t)-1;
    attr.minreq    = (uint32_t)-1;
    attr.fragsize  = target_latency_bytes;

    int error = 0;
    char *device = get_default_monitor();

    g_pa_stream = pa_simple_new(
        NULL,
        "WiFi Audio Streaming",
        PA_STREAM_RECORD,
        device,
        "Loopback Monitor",
        &ss,
        NULL,
        &attr,
        &error
    );

    if (!g_pa_stream) {
        set_error("pa_simple_new failed: %s", pa_strerror(error));
        return JNI_FALSE;
    }

    g_pa_initialized = 1;
    printf("[AudioEngine/PulseAudio] Started. %d ch, %d Hz, S16LE, device: %s\n",
           channels, sample_rate, device ? device : "default");
    return JNI_TRUE;
}

/*
 * pulse_read — bloccante ma a bassa latenza: ritorna quando ha riempito buf.
 * Restituisce JNI_TRUE in caso di successo.
 */
static jboolean pulse_read(int16_t *buf, int num_stereo_samples) {
    if (!g_pa_initialized || !g_pa_stream) return JNI_FALSE;

    int error = 0;
    size_t bytes = (size_t)num_stereo_samples * 2 * sizeof(int16_t);
    if (pa_simple_read(g_pa_stream, buf, bytes, &error) < 0) {
        set_error("pa_simple_read failed: %s", pa_strerror(error));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void pulse_stop(void) {
    if (!g_pa_initialized) return;
    g_pa_initialized = 0;
    if (g_pa_stream) {
        pa_simple_free(g_pa_stream);
        g_pa_stream = NULL;
    }
    printf("[AudioEngine/PulseAudio] Stopped.\n");
}

/* ─────────────────────────────────────────────────────────────────────────────
 * Linux — Virtual microphone via null-sink PulseAudio/PipeWire.
 * Crea dinamicamente un sink "WFAS_VirtualMic"; le app (Discord, ecc.) possono
 * selezionare "WFAS_VirtualMic.monitor" come input microfono.
 * Su PipeWire in modalità compat i comandi pactl funzionano ugualmente.
 * ───────────────────────────────────────────────────────────────────────────*/

static int             g_vsink_module_id = -1;
static pa_simple      *g_vsink_play      = NULL;
static const char     *g_vsink_name      = "WFAS_VirtualMic";

static int run_pactl_load(const char *sink_name, int rate, int channels) {
    char cmd[512];
    snprintf(cmd, sizeof(cmd),
        "pactl load-module module-null-sink "
        "sink_name=%s rate=%d channels=%d "
        "sink_properties=device.description=WiFi_Audio_Streaming_Virtual_Mic "
        "2>/dev/null",
        sink_name, rate, channels);
    FILE *fp = popen(cmd, "r");
    if (!fp) return -1;
    char line[64] = {0};
    if (!fgets(line, sizeof(line), fp)) { pclose(fp); return -1; }
    pclose(fp);
    int id = atoi(line);
    return id > 0 ? id : -1;
}

static void run_pactl_unload(int id) {
    if (id < 0) return;
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "pactl unload-module %d 2>/dev/null", id);
    int rc = system(cmd);
    (void)rc;
}

static jboolean linux_vsink_create(int sample_rate, int channels) {
    if (g_vsink_play != NULL) return JNI_TRUE;

    int id = run_pactl_load(g_vsink_name, sample_rate, channels);
    if (id < 0) {
        set_error("Impossibile creare null-sink PulseAudio (pactl non trovato o errore).");
        return JNI_FALSE;
    }
    g_vsink_module_id = id;

    pa_sample_spec ss;
    ss.format   = PA_SAMPLE_S16LE;
    ss.rate     = (uint32_t)sample_rate;
    ss.channels = (uint8_t)channels;

    int error = 0;
    g_vsink_play = pa_simple_new(
        NULL,
        "WFAS Virtual Mic",
        PA_STREAM_PLAYBACK,
        g_vsink_name,
        "Mic feed",
        &ss,
        NULL,
        NULL,
        &error
    );
    if (!g_vsink_play) {
        set_error("pa_simple_new(playback) fallita: %s", pa_strerror(error));
        run_pactl_unload(g_vsink_module_id);
        g_vsink_module_id = -1;
        return JNI_FALSE;
    }

    fprintf(stderr, "[AudioEngine/Linux] Virtual sink creato: %s (module id %d)\n",
            g_vsink_name, g_vsink_module_id);
    return JNI_TRUE;
}

static void linux_vsink_destroy(void) {
    if (g_vsink_play) {
        pa_simple_free(g_vsink_play);
        g_vsink_play = NULL;
    }
    if (g_vsink_module_id >= 0) {
        run_pactl_unload(g_vsink_module_id);
        fprintf(stderr, "[AudioEngine/Linux] Virtual sink rimosso (module id %d).\n",
                g_vsink_module_id);
        g_vsink_module_id = -1;
    }
}

static jboolean linux_vsink_write(const int16_t *pcm, int num_samples) {
    if (!g_vsink_play || !pcm || num_samples <= 0) return JNI_FALSE;
    int error = 0;
    size_t bytes = (size_t)num_samples * sizeof(int16_t);
    if (pa_simple_write(g_vsink_play, pcm, bytes, &error) < 0) {
        set_error("pa_simple_write fallita: %s", pa_strerror(error));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  macOS — stub C: l'implementazione reale è in audio_engine_mac.m
 * ═══════════════════════════════════════════════════════════════════════════*/
#elif defined(__APPLE__)

#include <stdarg.h>

/* Dichiarazioni forward: implementate in audio_engine_mac.m */
extern jboolean mac_engine_start(int sample_rate, int channels, int buffer_frames);
extern jint     mac_engine_read(int16_t *out_buf, int num_stereo_samples);
extern void     mac_engine_stop(void);
extern void     mac_engine_get_error(char *buf, int buf_size);

#else
#   include <stdarg.h>
#   warning "Piattaforma non supportata — AudioEngine restituirà sempre errore."
#endif


/* ═══════════════════════════════════════════════════════════════════════════
 *  Funzioni JNI esportate (stesso ABI su tutti gli OS)
 * ═══════════════════════════════════════════════════════════════════════════*/

/*
 * Determina il nome della funzione JNI a partire dal nome della classe Kotlin.
 * La classe Kotlin sarà:  AudioEngine  (top-level, nessun package nell'app corrente)
 * Stringa mangled:        Java_AudioEngine_nativeXxx
 */

JNIEXPORT jboolean JNICALL
Java_AudioEngine_nativeStart(JNIEnv *env, jobject thiz,
                              jint sample_rate, jint channels, jint buffer_frames) {
    (void)env; (void)thiz;
    g_last_error[0] = '\0';

#if defined(_WIN32)
    return wasapi_start((int)sample_rate, (int)channels);
#elif defined(__linux__)
    return pulse_start((int)sample_rate, (int)channels);
#elif defined(__APPLE__)
    return mac_engine_start((int)sample_rate, (int)channels, (int)buffer_frames);
#else
    set_error("Piattaforma non supportata");
    return JNI_FALSE;
#endif
}

/*
 * nativeRead — riempie outBuf con `numSamples` campioni int16 interleaved (L,R,L,R,...).
 * `numSamples` è il numero totale di short (= frame_stereo * 2).
 * Restituisce il numero di short effettivamente scritti (0 = silenzio/timeout, -1 = errore).
 */
JNIEXPORT jint JNICALL
Java_AudioEngine_nativeRead(JNIEnv *env, jobject thiz,
                             jshortArray out_buf, jint num_samples) {
    (void)thiz;

    jshort *buf = (*env)->GetShortArrayElements(env, out_buf, NULL);
    if (!buf) {
        set_error("GetShortArrayElements returned NULL");
        return -1;
    }

    jint result = 0;
    int stereo_frames = (int)num_samples / 2;

#if defined(_WIN32)
    result = wasapi_read((int16_t *)buf, stereo_frames) * 2;
#elif defined(__linux__)
    if (pulse_read((int16_t *)buf, stereo_frames) == JNI_TRUE)
        result = num_samples;
    else
        result = -1;
#elif defined(__APPLE__)
    result = mac_engine_read((int16_t *)buf, stereo_frames) * 2;
#else
    set_error("Piattaforma non supportata");
    result = -1;
#endif

    if (result > 0) {
        mic_mix_into((int16_t *)buf, (int)result);
    } else if (result == 0 && g_mic_enabled) {
        mic_lock();
        int avail = (g_mic_ring_w - g_mic_ring_r + MIC_RING_CAP_SAMPLES) % MIC_RING_CAP_SAMPLES;
        mic_unlock();
        if (avail > 0) {
            memset(buf, 0, (size_t)num_samples * sizeof(jshort));
            mic_mix_into((int16_t *)buf, (int)num_samples);
            result = num_samples;
        }
    }

    (*env)->ReleaseShortArrayElements(env, out_buf, buf, 0);
    return result;
}

JNIEXPORT void JNICALL
Java_AudioEngine_nativeStop(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;

#if defined(_WIN32)
    wasapi_stop();
#elif defined(__linux__)
    pulse_stop();
#elif defined(__APPLE__)
    mac_engine_stop();
#else
    (void)0;
#endif
}

JNIEXPORT jstring JNICALL
Java_AudioEngine_nativeGetError(JNIEnv *env, jobject thiz) {
    (void)thiz;

#if defined(__APPLE__)
    mac_engine_get_error(g_last_error, sizeof(g_last_error));
#endif

    return (*env)->NewStringUTF(env, g_last_error);
}

JNIEXPORT jboolean JNICALL
Java_AudioEngine_nativeMicSetMixEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    (void)env; (void)thiz;
    g_mic_enabled = (enabled == JNI_TRUE) ? 1 : 0;
    if (!g_mic_enabled) mic_ring_reset();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_AudioEngine_nativeMicSetVolume(JNIEnv *env, jobject thiz, jfloat volume) {
    (void)env; (void)thiz;
    float v = (float)volume;
    if (v < 0.0f) v = 0.0f;
    if (v > 4.0f) v = 4.0f;
    g_mic_volume = v;
}

JNIEXPORT jint JNICALL
Java_AudioEngine_nativeMicPushPcm(JNIEnv *env, jobject thiz, jshortArray pcm, jint num_samples) {
    (void)thiz;
    if (pcm == NULL) return 0;
    if (!g_mic_enabled) return 0;
    jshort *buf = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!buf) return 0;
    int n = (int)num_samples;
    if (n < 0) n = 0;
    jsize len = (*env)->GetArrayLength(env, pcm);
    if (n > (int)len) n = (int)len;
    mic_ring_push((const int16_t *)buf, n);
    (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT jboolean JNICALL
Java_AudioEngine_nativeVirtualSinkCreate(JNIEnv *env, jobject thiz, jint sample_rate, jint channels) {
    (void)env; (void)thiz;
    g_last_error[0] = '\0';
#if defined(__linux__)
    return linux_vsink_create((int)sample_rate, (int)channels);
#else
    (void)sample_rate; (void)channels;
    set_error("Creazione virtual sink supportata solo su Linux (PulseAudio/PipeWire).");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_AudioEngine_nativeVirtualSinkDestroy(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
#if defined(__linux__)
    linux_vsink_destroy();
#endif
}

JNIEXPORT jint JNICALL
Java_AudioEngine_nativeVirtualSinkWrite(JNIEnv *env, jobject thiz, jshortArray pcm, jint num_samples) {
    (void)thiz;
#if defined(__linux__)
    if (pcm == NULL) return -1;
    jshort *buf = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!buf) return -1;
    int n = (int)num_samples;
    if (n < 0) n = 0;
    jsize len = (*env)->GetArrayLength(env, pcm);
    if (n > (int)len) n = (int)len;
    jboolean ok = linux_vsink_write((const int16_t *)buf, n);
    (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
    return (ok == JNI_TRUE) ? (jint)n : -1;
#else
    (void)env; (void)pcm; (void)num_samples;
    return -1;
#endif
}

JNIEXPORT jstring JNICALL
Java_AudioEngine_nativeVirtualSinkName(JNIEnv *env, jobject thiz) {
    (void)thiz;
#if defined(__linux__)
    if (g_vsink_module_id >= 0 && g_vsink_play != NULL)
        return (*env)->NewStringUTF(env, g_vsink_name);
    return (*env)->NewStringUTF(env, "");
#else
    return (*env)->NewStringUTF(env, "");
#endif
}

JNIEXPORT jboolean JNICALL
Java_AudioEngine_nativeMicSinkOpen(JNIEnv *env, jobject thiz, jstring device_name, jint sample_rate, jint channels) {
    (void)thiz;
    g_last_error[0] = '\0';
#if defined(_WIN32)
    wchar_t wname[256];
    wname[0] = L'\0';
    if (device_name != NULL) {
        const jchar *jc = (*env)->GetStringChars(env, device_name, NULL);
        jsize jl = (*env)->GetStringLength(env, device_name);
        int copy = (jl < 255) ? (int)jl : 255;
        for (int i = 0; i < copy; i++) wname[i] = (wchar_t)jc[i];
        wname[copy] = L'\0';
        (*env)->ReleaseStringChars(env, device_name, jc);
    }
    return wasapi_sink_open(wname[0] ? wname : NULL, (int)sample_rate, (int)channels);
#else
    (void)env; (void)device_name; (void)sample_rate; (void)channels;
    set_error("MicSink WASAPI supportato solo su Windows.");
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_AudioEngine_nativeMicSinkWrite(JNIEnv *env, jobject thiz, jshortArray pcm, jint num_samples) {
    (void)thiz;
#if defined(_WIN32)
    if (pcm == NULL) return -1;
    jshort *buf = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!buf) return -1;
    int n = (int)num_samples;
    if (n < 0) n = 0;
    jsize len = (*env)->GetArrayLength(env, pcm);
    if (n > (int)len) n = (int)len;
    int pushed = wasapi_sink_push((const int16_t *)buf, n);
    (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
    return (jint)pushed;
#else
    (void)env; (void)pcm; (void)num_samples;
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_AudioEngine_nativeMicSinkClose(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
#if defined(_WIN32)
    wasapi_sink_close_internal();
#endif
}

JNIEXPORT jstring JNICALL
Java_AudioEngine_nativeMicSinkDeviceName(JNIEnv *env, jobject thiz) {
    (void)thiz;
#if defined(_WIN32)
    return (*env)->NewStringUTF(env, g_ws.device_name);
#else
    return (*env)->NewStringUTF(env, "");
#endif
}
