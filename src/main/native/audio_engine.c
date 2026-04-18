/*
 * audio_engine.c
 *
 * Motore di cattura audio nativo per WiFi Audio Streaming Desktop.
 * Implementa loopback audio (cattura di ciò che viene riprodotto) tramite:
 *   - Windows : WASAPI Loopback  (nessun driver virtuale richiesto)
 *   - Linux   : PulseAudio/PipeWire simple API  (monitor del sink di default)
 *   - macOS   : stub — delegato a audio_engine_mac.m (ScreenCaptureKit)
 *
 * Interfaccia JNI esposta verso Kotlin (package com.marcomorosi.wfas):
 *
 *   boolean AudioEngine_nativeStart(int sampleRate, int channels, int bufferFrames)
 *   boolean AudioEngine_nativeRead(short[] outBuf, int numSamples)
 *   void    AudioEngine_nativeStop()
 *   String  AudioEngine_nativeGetError()
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

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

/* ═══════════════════════════════════════════════════════════════════════════
 *  WINDOWS — WASAPI Loopback
 * ═══════════════════════════════════════════════════════════════════════════*/
#if defined(_WIN32)

#define WIN32_LEAN_AND_MEAN
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
    const int max_wait_ms = 2000;

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
static jboolean pulse_start(int sample_rate, int channels) {
    pa_sample_spec ss;
    ss.format   = PA_SAMPLE_S16LE;
    ss.rate     = (uint32_t)sample_rate;
    ss.channels = (uint8_t)channels;

    int error = 0;
    /*
     * Passiamo NULL come device_name: PulseAudio (e PipeWire in modalità
     * compat) interpreterà questo come "usa il monitor del sink di default".
     * Se l'utente ha rinominato il suo sink, possiamo forzare un nome
     * specifico aggiungendo un parametro, ma il default funziona nel 99% dei casi.
     */
    g_pa_stream = pa_simple_new(
        NULL,               /* server  — NULL = locale */
        "WiFi Audio Streaming",
        PA_STREAM_RECORD,
        NULL,               /* device  — NULL = monitor del sink di default */
        "Loopback Monitor",
        &ss,
        NULL,               /* channel map — default */
        NULL,               /* buffer attr — default */
        &error
    );

    if (!g_pa_stream) {
        set_error("pa_simple_new failed: %s", pa_strerror(error));
        return JNI_FALSE;
    }

    g_pa_initialized = 1;
    printf("[AudioEngine/PulseAudio] Started. %d ch, %d Hz, S16LE\n",
           channels, sample_rate);
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
