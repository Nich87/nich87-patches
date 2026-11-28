/*
 * Userspace USB Audio Class (UAC 1.0 / UAC 2.0) direct isochronous driver
 * using Linux usbdevfs.
 *
 * Adapted from decent-usb-audio-driver (https://github.com/Ma145/decent-player),
 * Copyright (c) 2026 Marcelo Silva, licensed under the MIT License, with
 * extensions for UAC 1.0 Full-Speed scheduling, decoupled subslot/bit-depth
 * formatting, bit-exact 24-bit float recovery, zero-allocation hot path, and
 * kernel driver reconnection on release.
 */

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <math.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#ifndef USBDEVFS_DISCONNECT
#define USBDEVFS_DISCONNECT _IO('U', 22)
#endif

#ifndef USBDEVFS_CONNECT
#define USBDEVFS_CONNECT _IO('U', 23)
#endif

#define TAG "VoidhackUsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Max ring dimensions (supports both UAC2 High-Speed 80x8=80ms and UAC1 Full-Speed 20x4=80ms)
#define MAX_URBS 80
#define MAX_PACKETS_PER_URB 8
#define URB_BUFFER_SIZE 8192

// Mirrors AudioQualityModels / Android AudioFormat / ExoPlayer2 PCM encoding constants
#define ENC_PCM_16BIT        2
#define ENC_PCM_FLOAT        4
#define ENC_PCM_24BIT_PACKED 21
#define ENC_PCM_32BIT        22
#define ENC_EXO_PCM_24BIT    0x20000000
#define ENC_EXO_PCM_32BIT    0x30000000

typedef struct {
    struct usbdevfs_urb *urb;
    uint8_t *buffer;
    int dataLength;
} UrbSlot;

typedef struct {
    int fd;
    int interfaceId;
    int endpointOut;
    int endpointFeedback;
    int feedbackPacketSize;
    int32_t sampleRate;
    int32_t channelCount;
    int32_t subslotBytes;   // Wire container width: 2, 3, or 4 bytes per sample
    int32_t bitResolution;  // Significant bits: 16, 24, or 32
    int32_t bytesPerFrame;  // subslotBytes * channelCount
    int32_t maxPacketSize;
    bool isHighSpeed;
    int32_t bInterval;

    double transfersPerSecond; // 8000 / 2^(bInterval-1) for HS, 1000 / bInterval for FS
    int32_t packetsPerUrb;
    int32_t numUrbs;
    int32_t pollSleepUs;

    atomic_bool running;
    atomic_int_fast64_t framesWritten;
    atomic_int urbsInFlight;
    atomic_int lastError;
    atomic_int_fast64_t measuredRateMilliHz;

    uint8_t *transferBuffer;
    int32_t transferBufferCapacity;
    uint8_t *mergeBuffer;
    int32_t mergeBufferCapacity;

    UrbSlot ring[MAX_URBS];
    int submitIdx;
    int reapIdx;
    bool ringAllocated;

    double frameAccumulator;
    double calibratedFpt; // Frames per ISO transfer (microframe for HS, frame for FS)

    uint8_t residualBuffer[URB_BUFFER_SIZE];
    int residualBytes;

    struct usbdevfs_urb *feedbackUrb;
    uint8_t feedbackBuffer[8];
    bool feedbackInFlight;
    int64_t feedbackCount;
} UsbAudioContext;

// ── Bit-exact PCM conversion ────────────────────────────────────────

static inline float clampf(float v) {
    if (v != v) return 0.0f; // NaN guard
    if (v > 1.0f) return 1.0f;
    if (v < -1.0f) return -1.0f;
    return v;
}

static inline int32_t floatToSigned24(float f) {
    // Recovers all 16,777,216 24-bit signed integers bit-for-bit after Apple Music's
    // FloatResamplingAudioProcessor scaling factor (1.0 / 0x7FFFFFFF).
    long v = lrintf(clampf(f) * 8388608.0f);
    if (v > 8388607L) return 8388607;
    if (v < -8388608L) return -8388608;
    return (int32_t)v;
}

static bool convertPcmToWire(
        const uint8_t *src,
        int inputBytes,
        int inputEncoding,
        UsbAudioContext *ctx,
        uint8_t **outPtr,
        int *outBytes,
        int *outFrames) {
    int inBps;
    switch (inputEncoding) {
        case ENC_PCM_16BIT:
            inBps = 2;
            break;
        case ENC_PCM_24BIT_PACKED:
        case ENC_EXO_PCM_24BIT:
            inBps = 3;
            break;
        case ENC_PCM_32BIT:
        case ENC_EXO_PCM_32BIT:
        case ENC_PCM_FLOAT:
            inBps = 4;
            break;
        default:
            return false;
    }

    int inFrameBytes = inBps * ctx->channelCount;
    if (inFrameBytes <= 0 || inputBytes < inFrameBytes) return false;
    int frames = inputBytes / inFrameBytes;
    int totalSamples = frames * ctx->channelCount;
    int neededOutBytes = totalSamples * ctx->subslotBytes;

    // Zero-copy fast paths when wire subslot matches integer PCM directly.
    if ((inputEncoding == ENC_PCM_16BIT && ctx->subslotBytes == 2) ||
        ((inputEncoding == ENC_PCM_24BIT_PACKED || inputEncoding == ENC_EXO_PCM_24BIT) && ctx->subslotBytes == 3) ||
        ((inputEncoding == ENC_PCM_32BIT || inputEncoding == ENC_EXO_PCM_32BIT) && ctx->subslotBytes == 4)) {
        *outPtr = (uint8_t *)src;
        *outBytes = neededOutBytes;
        *outFrames = frames;
        return true;
    }

    if (!ctx->transferBuffer || ctx->transferBufferCapacity < neededOutBytes) {
        uint8_t *nb = (uint8_t *)realloc(ctx->transferBuffer, (size_t)neededOutBytes);
        if (!nb) return false;
        ctx->transferBuffer = nb;
        ctx->transferBufferCapacity = neededOutBytes;
    }
    uint8_t *dst = ctx->transferBuffer;

    if (inputEncoding == ENC_PCM_16BIT) {
        if (ctx->subslotBytes == 3) {
            for (int i = 0; i < totalSamples; i++) {
                dst[i * 3 + 0] = 0;
                dst[i * 3 + 1] = src[i * 2 + 0];
                dst[i * 3 + 2] = src[i * 2 + 1];
            }
        } else if (ctx->subslotBytes == 4) {
            for (int i = 0; i < totalSamples; i++) {
                dst[i * 4 + 0] = 0;
                dst[i * 4 + 1] = 0;
                dst[i * 4 + 2] = src[i * 2 + 0];
                dst[i * 4 + 3] = src[i * 2 + 1];
            }
        } else {
            return false;
        }
    } else if (inputEncoding == ENC_PCM_24BIT_PACKED || inputEncoding == ENC_EXO_PCM_24BIT) {
        if (ctx->subslotBytes == 4) {
            // Exact 24-in-32 MSB-aligned (low byte zero-padded, zero arithmetic).
            for (int i = 0; i < totalSamples; i++) {
                dst[i * 4 + 0] = 0;
                dst[i * 4 + 1] = src[i * 3 + 0];
                dst[i * 4 + 2] = src[i * 3 + 1];
                dst[i * 4 + 3] = src[i * 3 + 2];
            }
        } else if (ctx->subslotBytes == 2) {
            for (int i = 0; i < totalSamples; i++) {
                dst[i * 2 + 0] = src[i * 3 + 1];
                dst[i * 2 + 1] = src[i * 3 + 2];
            }
        } else {
            return false;
        }
    } else if (inputEncoding == ENC_PCM_32BIT || inputEncoding == ENC_EXO_PCM_32BIT) {
        if (ctx->subslotBytes == 3) {
            for (int i = 0; i < totalSamples; i++) {
                dst[i * 3 + 0] = src[i * 4 + 1];
                dst[i * 3 + 1] = src[i * 4 + 2];
                dst[i * 3 + 2] = src[i * 4 + 3];
            }
        } else if (ctx->subslotBytes == 2) {
            for (int i = 0; i < totalSamples; i++) {
                dst[i * 2 + 0] = src[i * 4 + 2];
                dst[i * 2 + 1] = src[i * 4 + 3];
            }
        } else {
            return false;
        }
    } else if (inputEncoding == ENC_PCM_FLOAT) {
        const float *fsrc = (const float *)src;
        if (ctx->subslotBytes == 2) {
            for (int i = 0; i < totalSamples; i++) {
                long v = lrintf(clampf(fsrc[i]) * 32768.0f);
                if (v > 32767L) v = 32767L;
                if (v < -32768L) v = -32768L;
                int16_t s = (int16_t)v;
                dst[i * 2 + 0] = (uint8_t)(s & 0xFF);
                dst[i * 2 + 1] = (uint8_t)((s >> 8) & 0xFF);
            }
        } else if (ctx->subslotBytes == 3) {
            for (int i = 0; i < totalSamples; i++) {
                int32_t v = floatToSigned24(fsrc[i]);
                dst[i * 3 + 0] = (uint8_t)(v & 0xFF);
                dst[i * 3 + 1] = (uint8_t)((v >> 8) & 0xFF);
                dst[i * 3 + 2] = (uint8_t)((v >> 16) & 0xFF);
            }
        } else if (ctx->subslotBytes == 4) {
            if (ctx->bitResolution <= 24) {
                for (int i = 0; i < totalSamples; i++) {
                    int32_t v = floatToSigned24(fsrc[i]);
                    dst[i * 4 + 0] = 0;
                    dst[i * 4 + 1] = (uint8_t)(v & 0xFF);
                    dst[i * 4 + 2] = (uint8_t)((v >> 8) & 0xFF);
                    dst[i * 4 + 3] = (uint8_t)((v >> 16) & 0xFF);
                }
            } else {
                for (int i = 0; i < totalSamples; i++) {
                    long long v = llrint((double)clampf(fsrc[i]) * 2147483648.0);
                    if (v > 2147483647LL) v = 2147483647LL;
                    if (v < -2147483648LL) v = -2147483648LL;
                    int32_t s = (int32_t)v;
                    dst[i * 4 + 0] = (uint8_t)(s & 0xFF);
                    dst[i * 4 + 1] = (uint8_t)((s >> 8) & 0xFF);
                    dst[i * 4 + 2] = (uint8_t)((s >> 16) & 0xFF);
                    dst[i * 4 + 3] = (uint8_t)((s >> 24) & 0xFF);
                }
            }
        } else {
            return false;
        }
    } else {
        return false;
    }

    *outPtr = dst;
    *outBytes = neededOutBytes;
    *outFrames = frames;
    return true;
}

// ── Ring buffer management ──────────────────────────────────────────

static bool allocRing(UsbAudioContext *ctx) {
    size_t urbStructSize = sizeof(struct usbdevfs_urb) +
                           (size_t)ctx->packetsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);
    for (int i = 0; i < ctx->numUrbs; i++) {
        ctx->ring[i].urb = (struct usbdevfs_urb *)calloc(1, urbStructSize);
        ctx->ring[i].buffer = (uint8_t *)malloc(URB_BUFFER_SIZE);
        ctx->ring[i].dataLength = 0;
        if (!ctx->ring[i].urb || !ctx->ring[i].buffer) {
            LOGE("allocRing: OOM at slot %d", i);
            for (int j = 0; j <= i; j++) {
                free(ctx->ring[j].urb);
                free(ctx->ring[j].buffer);
                ctx->ring[j].urb = NULL;
                ctx->ring[j].buffer = NULL;
            }
            return false;
        }
    }
    ctx->ringAllocated = true;
    return true;
}

static void freeRing(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated) return;
    for (int i = 0; i < ctx->numUrbs; i++) {
        free(ctx->ring[i].urb);
        free(ctx->ring[i].buffer);
        ctx->ring[i].urb = NULL;
        ctx->ring[i].buffer = NULL;
    }
    ctx->ringAllocated = false;
}

// ── Async feedback endpoint (UAC2 Q16.16 4-byte & UAC1 Q10.14 3-byte) ──

static double parseFeedbackFpt(const UsbAudioContext *ctx, const uint8_t *fb, int actualLen) {
    double nominalFpt = (double)ctx->sampleRate / ctx->transfersPerSecond;
    double low = nominalFpt * 0.985;
    double high = nominalFpt * 1.015;

    if (actualLen >= 4) {
        // UAC 2.0 High-Speed standard Q16.16 (frames per microframe, scaled to interval)
        uint32_t raw = (uint32_t)fb[0] | ((uint32_t)fb[1] << 8) |
                       ((uint32_t)fb[2] << 16) | ((uint32_t)fb[3] << 24);
        double fpmf = (double)raw / 65536.0;
        // If bInterval > 1 on HS, each ISO transfer spans 2^(bInterval-1) microframes.
        int mult = (ctx->isHighSpeed && ctx->bInterval > 1 && ctx->bInterval <= 8)
                ? (1 << (ctx->bInterval - 1)) : 1;
        double candidate = fpmf * (double)mult;
        if (candidate > low && candidate < high) return candidate;
        if (fpmf > low && fpmf < high) return fpmf;
    }
    if (actualLen >= 3) {
        // UAC 1.0 Full-Speed standard Q10.14 (frames per 1ms frame)
        uint32_t raw = (uint32_t)fb[0] | ((uint32_t)fb[1] << 8) | ((uint32_t)fb[2] << 16);
        double fpf1014 = (double)raw / 16384.0;
        if (!ctx->isHighSpeed) {
            int mult = (ctx->bInterval > 1 && ctx->bInterval <= 8) ? ctx->bInterval : 1;
            double candidate = fpf1014 * (double)mult;
            if (candidate > low && candidate < high) return candidate;
            if (fpf1014 > low && fpf1014 < high) return fpf1014;
        } else {
            // Rare HS 3-byte Q12.13 feedback quirk
            double fpmf = (double)raw / 8192.0;
            if (fpmf > low && fpmf < high) return fpmf;
        }
    }
    return 0.0;
}

static bool allocFeedbackUrb(UsbAudioContext *ctx) {
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    ctx->feedbackUrb = (struct usbdevfs_urb *)calloc(1, sz);
    if (!ctx->feedbackUrb) return false;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));
    return true;
}

static bool submitFeedbackUrb(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback <= 0 || !ctx->feedbackUrb || ctx->feedbackInFlight) return false;

    int pktLen = ctx->feedbackPacketSize;
    if (pktLen < 3) pktLen = ctx->isHighSpeed ? 4 : 3;
    if (pktLen > (int)sizeof(ctx->feedbackBuffer)) pktLen = (int)sizeof(ctx->feedbackBuffer);

    struct usbdevfs_urb *u = ctx->feedbackUrb;
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, sz);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointFeedback;
    u->buffer = ctx->feedbackBuffer;
    u->buffer_length = pktLen;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = (unsigned int)pktLen;

    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) < 0) {
        LOGW("submitFeedbackUrb: failed errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    ctx->feedbackInFlight = true;
    return true;
}

static double probeInitialFeedback(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback <= 0 || !ctx->feedbackUrb) return 0.0;
    if (!submitFeedbackUrb(ctx)) return 0.0;

    struct usbdevfs_urb *c = NULL;
    for (int i = 0; i < 20; i++) {
        usleep(500);
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c == ctx->feedbackUrb) {
            ctx->feedbackInFlight = false;
            int actual = (int)ctx->feedbackUrb->iso_frame_desc[0].actual_length;
            return parseFeedbackFpt(ctx, ctx->feedbackBuffer, actual);
        }
        if (ret < 0 && errno != EAGAIN) {
            break;
        }
    }
    // Timed out — discard cleanly
    ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
    for (int i = 0; i < 20; i++) {
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c == ctx->feedbackUrb) break;
        usleep(100);
    }
    ctx->feedbackInFlight = false;
    return 0.0;
}

static void handleFeedbackCompletion(UsbAudioContext *ctx) {
    ctx->feedbackInFlight = false;
    ctx->feedbackCount++;
    int actual = (int)ctx->feedbackUrb->iso_frame_desc[0].actual_length;
    double newFpt = parseFeedbackFpt(ctx, ctx->feedbackBuffer, actual);
    if (newFpt > 0.0) {
        ctx->calibratedFpt = newFpt;
        int64_t milliHz = llround(newFpt * ctx->transfersPerSecond * 1000.0);
        atomic_store_explicit(&ctx->measuredRateMilliHz, milliHz, memory_order_relaxed);
    }
    if (atomic_load_explicit(&ctx->running, memory_order_acquire)) {
        submitFeedbackUrb(ctx);
    }
}

// ── ISO URB submit / reap / drain ───────────────────────────────────

static int submitRingUrb(UsbAudioContext *ctx, const int *pktSizes, int numPackets, int totalBytes) {
    UrbSlot *slot = &ctx->ring[ctx->submitIdx];
    struct usbdevfs_urb *u = slot->urb;

    size_t clearSize = sizeof(struct usbdevfs_urb) +
                       (size_t)numPackets * sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, clearSize);

    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointOut;
    u->buffer = slot->buffer;
    u->buffer_length = totalBytes;
    u->number_of_packets = numPackets;
    for (int i = 0; i < numPackets; i++) {
        u->iso_frame_desc[i].length = (unsigned int)pktSizes[i];
    }

    slot->dataLength = totalBytes;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, u);
    if (ret < 0) {
        int err = errno;
        atomic_store_explicit(&ctx->lastError, err, memory_order_relaxed);
        LOGE("SUBMITURB FAILED slot=%d ep=0x%02x bytes=%d pkts=%d errno=%d (%s)",
             ctx->submitIdx, ctx->endpointOut, totalBytes, numPackets, err, strerror(err));
        return -err;
    }

    ctx->submitIdx = (ctx->submitIdx + 1) % ctx->numUrbs;
    atomic_fetch_add_explicit(&ctx->urbsInFlight, 1, memory_order_release);
    return 0;
}

static int reapOldestUrb(UsbAudioContext *ctx, int timeoutMs, bool cancelOnStop) {
    struct usbdevfs_urb *c = NULL;
    int stepUs = ctx->pollSleepUs > 0 ? ctx->pollSleepUs : 125;
    int iterations = (timeoutMs * 1000) / stepUs;
    if (iterations < 1) iterations = 1;

    for (int i = 0; i < iterations; i++) {
        if (cancelOnStop && !atomic_load_explicit(&ctx->running, memory_order_acquire)) {
            return -ECANCELED;
        }
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c != NULL) {
            if (c == ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx);
                c = NULL;
                continue;
            }
            ctx->reapIdx = (ctx->reapIdx + 1) % ctx->numUrbs;
            atomic_fetch_sub_explicit(&ctx->urbsInFlight, 1, memory_order_release);
            return 0;
        }
        if (ret < 0 && errno != EAGAIN) {
            int err = errno;
            atomic_store_explicit(&ctx->lastError, err, memory_order_relaxed);
            LOGE("reapOldestUrb: error errno=%d (%s)", err, strerror(err));
            return -err;
        }
        usleep((useconds_t)stepUs);
    }
    return -ETIMEDOUT;
}

static int drainAllUrbs(UsbAudioContext *ctx) {
    int drained = 0;
    int initialCount = atomic_load_explicit(&ctx->urbsInFlight, memory_order_acquire);

    if (ctx->feedbackInFlight && ctx->feedbackUrb) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
        struct usbdevfs_urb *c = NULL;
        for (int i = 0; i < 60; i++) {
            int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
            if (ret == 0 && c == ctx->feedbackUrb) break;
            if (ret < 0 && errno != EAGAIN) break;
            usleep(100);
        }
        ctx->feedbackInFlight = false;
    }

    while (atomic_load_explicit(&ctx->urbsInFlight, memory_order_acquire) > 0) {
        int res = reapOldestUrb(ctx, 250, false);
        if (res == 0) {
            drained++;
        } else {
            break;
        }
    }

    int remaining = atomic_load_explicit(&ctx->urbsInFlight, memory_order_acquire);
    if (remaining > 0) {
        LOGW("drainAllUrbs: %d/%d reaped naturally, discarding %d", drained, initialCount, remaining);
        for (int i = 0; i < remaining; i++) {
            int slotIdx = (ctx->reapIdx + i) % ctx->numUrbs;
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->ring[slotIdx].urb);
        }
        int reaped = 0;
        for (int attempt = 0; attempt < 300 && reaped < remaining; attempt++) {
            struct usbdevfs_urb *c = NULL;
            int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
            if (ret == 0 && c != NULL) {
                reaped++;
            } else if (ret < 0 && errno != EAGAIN) {
                break;
            } else {
                usleep(1000);
            }
        }
        drained += reaped;

        // Flush any stale completions left in the kernel event ring.
        for (int i = 0; i < 16; i++) {
            struct usbdevfs_urb *c = NULL;
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c != NULL) {
                drained++;
            } else {
                break;
            }
        }
    }

    atomic_store_explicit(&ctx->urbsInFlight, 0, memory_order_release);
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    return drained;
}

static int submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes) {
    const uint8_t *data = pcmData;
    int dataLen = totalBytes;

    if (ctx->residualBytes > 0 && totalBytes > 0) {
        dataLen = ctx->residualBytes + totalBytes;
        if (!ctx->mergeBuffer || ctx->mergeBufferCapacity < dataLen) {
            uint8_t *nb = (uint8_t *)realloc(ctx->mergeBuffer, (size_t)dataLen);
            if (!nb) {
                ctx->residualBytes = 0;
                data = pcmData;
                dataLen = totalBytes;
            } else {
                ctx->mergeBuffer = nb;
                ctx->mergeBufferCapacity = dataLen;
                memcpy(ctx->mergeBuffer, ctx->residualBuffer, (size_t)ctx->residualBytes);
                memcpy(ctx->mergeBuffer + ctx->residualBytes, pcmData, (size_t)totalBytes);
                data = ctx->mergeBuffer;
                ctx->residualBytes = 0;
            }
        } else {
            memcpy(ctx->mergeBuffer, ctx->residualBuffer, (size_t)ctx->residualBytes);
            memcpy(ctx->mergeBuffer + ctx->residualBytes, pcmData, (size_t)totalBytes);
            data = ctx->mergeBuffer;
            ctx->residualBytes = 0;
        }
    }

    int offset = 0;
    if (ctx->endpointFeedback > 0 && !ctx->feedbackInFlight) {
        submitFeedbackUrb(ctx);
    }
    double fpt = ctx->calibratedFpt;
    int maxPkt = ctx->maxPacketSize > 0 ? ctx->maxPacketSize : URB_BUFFER_SIZE;

    while (offset < dataLen && atomic_load_explicit(&ctx->running, memory_order_acquire)) {
        int pktSizes[MAX_PACKETS_PER_URB];
        int numPackets = 0;
        int urbBytes = 0;
        double savedAcc = ctx->frameAccumulator;

        for (int p = 0; p < ctx->packetsPerUrb; p++) {
            int remaining = dataLen - offset - urbBytes;
            if (remaining <= 0) break;

            ctx->frameAccumulator += fpt;
            int frames = (int)ctx->frameAccumulator;
            if (frames < 1) frames = 1;
            int b = frames * ctx->bytesPerFrame;

            // Clamp to endpoint max packet size (and URB buffer safety limit)
            if (b > maxPkt) {
                int clampedFrames = maxPkt / ctx->bytesPerFrame;
                if (clampedFrames < 1) clampedFrames = 1;
                frames = clampedFrames;
                b = frames * ctx->bytesPerFrame;
            }
            if (urbBytes + b > URB_BUFFER_SIZE) {
                break;
            }
            if (b > remaining) {
                // Roll back accumulator before breaking so we don't lose fractional timing
                ctx->frameAccumulator -= fpt;
                break;
            }

            ctx->frameAccumulator -= (double)frames;
            pktSizes[p] = b;
            urbBytes += b;
            numPackets++;
        }

        if (numPackets <= 0 || urbBytes <= 0) {
            ctx->frameAccumulator = savedAcc;
            break;
        }

        // Never submit short URBs — preserve leftover bytes in residualBuffer to prevent clicks.
        if (numPackets < ctx->packetsPerUrb) {
            ctx->frameAccumulator = savedAcc;
            int leftover = dataLen - offset;
            if (leftover > 0 && leftover <= URB_BUFFER_SIZE) {
                memcpy(ctx->residualBuffer, data + offset, (size_t)leftover);
                ctx->residualBytes = leftover;
            }
            return 0;
        }

        if (atomic_load_explicit(&ctx->urbsInFlight, memory_order_acquire) >= ctx->numUrbs) {
            int res = reapOldestUrb(ctx, 250, true);
            if (res == -ECANCELED) return res;
            if (res < 0) {
                LOGE("submitPcmToUrbs: reap failed (%d), stopping stream", res);
                drainAllUrbs(ctx);
                atomic_store_explicit(&ctx->running, false, memory_order_release);
                return res;
            }
        }

        UrbSlot *slot = &ctx->ring[ctx->submitIdx];
        memcpy(slot->buffer, data + offset, (size_t)urbBytes);

        int sret = submitRingUrb(ctx, pktSizes, numPackets, urbBytes);
        if (sret < 0) {
            atomic_store_explicit(&ctx->running, false, memory_order_release);
            return sret;
        }
        offset += urbBytes;
    }

    int leftover = dataLen - offset;
    if (leftover > 0 && leftover <= URB_BUFFER_SIZE && ctx->residualBytes == 0) {
        memcpy(ctx->residualBuffer, data + offset, (size_t)leftover);
        ctx->residualBytes = leftover;
    }
    return 0;
}

// ── JNI Entry Points (app.voidhack.extension.applemusic.quality.UsbDirectNative) ──

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL \
    Java_app_voidhack_extension_applemusic_quality_UsbDirectNative_##name

JNI_FN(jlong, nativeCreate)(
        JNIEnv *env, jclass cls,
        jint fd, jint ifaceId, jint epOut, jint epFb, jint fbPktSize,
        jint rate, jint channels, jint subslotBytes, jint bitResolution,
        jint maxPktSize, jboolean isHighSpeed, jint bInterval) {
    (void)env;
    (void)cls;
    if (fd < 0 || rate <= 0 || channels <= 0 ||
        (subslotBytes != 2 && subslotBytes != 3 && subslotBytes != 4)) {
        return 0;
    }

    UsbAudioContext *ctx = (UsbAudioContext *)calloc(1, sizeof(UsbAudioContext));
    if (!ctx) return 0;

    ctx->fd = fd;
    ctx->interfaceId = ifaceId;
    ctx->endpointOut = epOut;
    ctx->endpointFeedback = epFb;
    ctx->feedbackPacketSize = fbPktSize;
    ctx->sampleRate = rate;
    ctx->channelCount = channels;
    ctx->subslotBytes = subslotBytes;
    ctx->bitResolution = bitResolution > 0 ? bitResolution : (subslotBytes * 8);
    ctx->bytesPerFrame = subslotBytes * channels;
    ctx->maxPacketSize = maxPktSize;
    ctx->isHighSpeed = (isHighSpeed == JNI_TRUE);
    ctx->bInterval = bInterval > 0 ? bInterval : 1;

    if (ctx->isHighSpeed) {
        int clampedInterval = ctx->bInterval > 4 ? 4 : ctx->bInterval;
        int microframesPerTransfer = 1 << (clampedInterval - 1);
        ctx->transfersPerSecond = 8000.0 / (double)microframesPerTransfer;
        ctx->packetsPerUrb = 8 / microframesPerTransfer;
        if (ctx->packetsPerUrb < 1) ctx->packetsPerUrb = 1;
        if (ctx->packetsPerUrb > MAX_PACKETS_PER_URB) ctx->packetsPerUrb = MAX_PACKETS_PER_URB;
        ctx->numUrbs = MAX_URBS; // ~80 ms total ring
        ctx->pollSleepUs = 125;
    } else {
        // UAC 1.0 Full-Speed: 1000 frames/sec (1 ms per frame)
        int clampedInterval = ctx->bInterval > 4 ? 4 : ctx->bInterval;
        ctx->transfersPerSecond = 1000.0 / (double)clampedInterval;
        ctx->packetsPerUrb = (clampedInterval == 1) ? 4 : 2;
        if (ctx->packetsPerUrb > MAX_PACKETS_PER_URB) ctx->packetsPerUrb = MAX_PACKETS_PER_URB;
        ctx->numUrbs = 20; // 20 * 4ms = ~80 ms total ring
        ctx->pollSleepUs = 500;
    }

    ctx->calibratedFpt = (double)rate / ctx->transfersPerSecond;
    atomic_init(&ctx->running, false);
    atomic_init(&ctx->framesWritten, 0);
    atomic_init(&ctx->urbsInFlight, 0);
    atomic_init(&ctx->lastError, 0);
    atomic_init(&ctx->measuredRateMilliHz, 0);

    if (!allocRing(ctx)) {
        free(ctx);
        return 0;
    }
    if (!allocFeedbackUrb(ctx)) {
        freeRing(ctx);
        free(ctx);
        return 0;
    }

    LOGI("Create: fd=%d iface=%d epOut=0x%02x epFb=0x%02x rate=%d ch=%d subslot=%d bits=%d maxPkt=%d hs=%d int=%d ring=%dx%d",
         fd, ifaceId, epOut, epFb, rate, channels, subslotBytes, ctx->bitResolution,
         maxPktSize, ctx->isHighSpeed ? 1 : 0, ctx->bInterval, ctx->numUrbs, ctx->packetsPerUrb);
    return (jlong)(intptr_t)ctx;
}

JNI_FN(jboolean, nativeStart)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return JNI_FALSE;

    atomic_store_explicit(&ctx->running, true, memory_order_release);
    atomic_store_explicit(&ctx->framesWritten, 0, memory_order_release);
    atomic_store_explicit(&ctx->urbsInFlight, 0, memory_order_release);
    atomic_store_explicit(&ctx->lastError, 0, memory_order_release);
    atomic_store_explicit(&ctx->measuredRateMilliHz, 0, memory_order_release);
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    ctx->feedbackCount = 0;

    double nominalFpt = (double)ctx->sampleRate / ctx->transfersPerSecond;
    ctx->calibratedFpt = nominalFpt;

    if (ctx->endpointFeedback > 0) {
        double fb = probeInitialFeedback(ctx);
        if (fb > 0.0) {
            ctx->calibratedFpt = fb;
            int64_t milliHz = llround(fb * ctx->transfersPerSecond * 1000.0);
            atomic_store_explicit(&ctx->measuredRateMilliHz, milliHz, memory_order_relaxed);
            LOGI("Start: initial feedback=%.4f fpt (%.1f Hz)", fb, fb * ctx->transfersPerSecond);
        }
        submitFeedbackUrb(ctx);
    }
    return JNI_TRUE;
}

JNI_FN(jint, nativeWritePcm)(
        JNIEnv *env, jclass cls, jlong handle,
        jbyteArray pcm, jint offset, jint length, jint inputEncoding) {
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx || !atomic_load_explicit(&ctx->running, memory_order_acquire)) return -ECANCELED;
    if (!pcm || length <= 0 || offset < 0) return 0;

    jint arrLen = (*env)->GetArrayLength(env, pcm);
    if (offset + length > arrLen) return -EINVAL;

    jbyte *raw = (*env)->GetByteArrayElements(env, pcm, NULL);
    if (!raw) return -ENOMEM;

    uint8_t *wireData = NULL;
    int wireBytes = 0;
    int frames = 0;
    bool ok = convertPcmToWire(
            (const uint8_t *)(raw + offset),
            length,
            inputEncoding,
            ctx,
            &wireData,
            &wireBytes,
            &frames);
    if (!ok) {
        (*env)->ReleaseByteArrayElements(env, pcm, raw, JNI_ABORT);
        return -EINVAL;
    }

    // If zero-copy path returned raw pointer directly, submit while pinned, else release first.
    int sret;
    if (wireData == (const uint8_t *)(raw + offset)) {
        // Copy into transferBuffer so we never block holding JNI array elements!
        if (!ctx->transferBuffer || ctx->transferBufferCapacity < wireBytes) {
            uint8_t *nb = (uint8_t *)realloc(ctx->transferBuffer, (size_t)wireBytes);
            if (!nb) {
                (*env)->ReleaseByteArrayElements(env, pcm, raw, JNI_ABORT);
                return -ENOMEM;
            }
            ctx->transferBuffer = nb;
            ctx->transferBufferCapacity = wireBytes;
        }
        memcpy(ctx->transferBuffer, wireData, (size_t)wireBytes);
        (*env)->ReleaseByteArrayElements(env, pcm, raw, JNI_ABORT);
        sret = submitPcmToUrbs(ctx, ctx->transferBuffer, wireBytes);
    } else {
        (*env)->ReleaseByteArrayElements(env, pcm, raw, JNI_ABORT);
        sret = submitPcmToUrbs(ctx, wireData, wireBytes);
    }

    if (sret < 0) {
        return sret;
    }
    atomic_fetch_add_explicit(&ctx->framesWritten, frames, memory_order_release);
    return frames;
}

JNI_FN(void, nativeStop)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return;
    atomic_store_explicit(&ctx->running, false, memory_order_release);
}

JNI_FN(void, nativeFlush)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    atomic_store_explicit(&ctx->framesWritten, 0, memory_order_release);
}

JNI_FN(jint, nativeDrainUrbs)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return 0;
    return drainAllUrbs(ctx);
}

JNI_FN(void, nativeDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return;
    atomic_store_explicit(&ctx->running, false, memory_order_release);
    if (atomic_load_explicit(&ctx->urbsInFlight, memory_order_acquire) > 0 || ctx->feedbackInFlight) {
        drainAllUrbs(ctx);
    }
    freeRing(ctx);
    free(ctx->feedbackUrb);
    free(ctx->transferBuffer);
    free(ctx->mergeBuffer);
    free(ctx);
}

JNI_FN(jboolean, nativeIsRunning)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return JNI_FALSE;
    return atomic_load_explicit(&ctx->running, memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jlong, nativeGetFramesWritten)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return 0;
    return (jlong)atomic_load_explicit(&ctx->framesWritten, memory_order_acquire);
}

JNI_FN(jint, nativeGetUrbsInFlight)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return 0;
    return (jint)atomic_load_explicit(&ctx->urbsInFlight, memory_order_acquire);
}

JNI_FN(jint, nativeGetFramesPerUrb)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return 0;
    int64_t measuredMilliHz = atomic_load_explicit(&ctx->measuredRateMilliHz, memory_order_relaxed);
    double rate = measuredMilliHz > 0 ? measuredMilliHz / 1000.0 : ctx->sampleRate;
    return (jint)llround(rate * ctx->packetsPerUrb / ctx->transfersPerSecond);
}

JNI_FN(jint, nativeGetMeasuredRateHz)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    UsbAudioContext *ctx = (UsbAudioContext *)(intptr_t)handle;
    if (!ctx) return 0;
    int64_t mhz = atomic_load_explicit(&ctx->measuredRateMilliHz, memory_order_relaxed);
    return mhz > 0 ? (jint)((mhz + 500) / 1000) : 0;
}

JNI_FN(jboolean, nativeReconnectKernelDriver)(
        JNIEnv *env, jclass cls, jint fd, jint acInterfaceId, jint asInterfaceId) {
    (void)env;
    (void)cls;
    if (fd < 0) return JNI_FALSE;
    bool ok = true;
    if (acInterfaceId >= 0) {
        struct usbdevfs_ioctl cmd;
        memset(&cmd, 0, sizeof(cmd));
        cmd.ifno = acInterfaceId;
        cmd.ioctl_code = USBDEVFS_CONNECT;
        if (ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0) ok = false;
    }
    if (asInterfaceId >= 0 && asInterfaceId != acInterfaceId) {
        struct usbdevfs_ioctl cmd;
        memset(&cmd, 0, sizeof(cmd));
        cmd.ifno = asInterfaceId;
        cmd.ioctl_code = USBDEVFS_CONNECT;
        if (ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0) ok = false;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
