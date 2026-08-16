/*
 * Android USB-Host CDC ACM port layer for esp-serial-flasher.
 *
 * This file implements the esp_loader_port_ops_t callbacks using Android's
 * UsbDeviceConnection.bulkTransfer() method, invoked through JNI.
 */

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

#include "esp_loader.h"
#include "esp_loader_io.h"

#define TAG "Esp32Flasher"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Global JNI state protected by a mutex. */
static pthread_mutex_t g_jni_mutex = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm = NULL;
static jclass g_helper_class = NULL;
static jmethodID g_bulk_transfer_method = NULL;

/* Per-instance port state. */
struct android_usb_port_s {
    esp_loader_port_t base;
    jobject helper;        /* GlobalRef to Esp32Flasher instance. */
    int in_ep_addr;
    int out_ep_addr;
    int max_packet_size;
    uint64_t timer_end_us;
};

typedef struct android_usb_port_s android_usb_port_t;

/* Called from JNI_OnLoad or nativeInit to cache VM and method IDs. */
int android_usb_port_jni_init(JavaVM *vm, JNIEnv *env, jclass helper_class)
{
    pthread_mutex_lock(&g_jni_mutex);
    g_vm = vm;

    if (g_helper_class == NULL) {
        g_helper_class = (*env)->NewGlobalRef(env, helper_class);
        if (g_helper_class == NULL) {
            pthread_mutex_unlock(&g_jni_mutex);
            return -1;
        }
    }

    g_bulk_transfer_method = (*env)->GetMethodID(
        env, g_helper_class,
        "usbBulkTransfer", "(I[BII)I");

    if (g_bulk_transfer_method == NULL) {
        LOGE("Failed to find usbBulkTransfer method");
        pthread_mutex_unlock(&g_jni_mutex);
        return -1;
    }

    pthread_mutex_unlock(&g_jni_mutex);
    return 0;
}

static JNIEnv *get_jni_env(void)
{
    JNIEnv *env = NULL;
    if (g_vm == NULL) {
        return NULL;
    }
    jint ret = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
            return NULL;
        }
    } else if (ret != JNI_OK) {
        return NULL;
    }
    return env;
}

static uint64_t get_time_us(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (uint64_t)tv.tv_sec * 1000000ULL + (uint64_t)tv.tv_usec;
}

/* ─── Port callbacks ───────────────────────────────────────────────────── */

static esp_loader_error_t android_usb_port_init(esp_loader_port_t *port)
{
    (void)port;
    return ESP_LOADER_SUCCESS;
}

static void android_usb_port_deinit(esp_loader_port_t *port)
{
    android_usb_port_t *p = (android_usb_port_t *)port;
    JNIEnv *env = get_jni_env();
    if (env != NULL && p->helper != NULL) {
        (*env)->DeleteGlobalRef(env, p->helper);
        p->helper = NULL;
    }
}

static void android_usb_enter_bootloader(esp_loader_port_t *port)
{
    (void)port;
    /* The Kotlin side already sent the 'bootloader' command over CDC. */
}

static void android_usb_reset_target(esp_loader_port_t *port)
{
    (void)port;
    /* ROM loader will boot the new firmware when we disconnect. */
}

static void android_usb_start_timer(esp_loader_port_t *port, uint32_t ms)
{
    android_usb_port_t *p = (android_usb_port_t *)port;
    p->timer_end_us = get_time_us() + (uint64_t)ms * 1000ULL;
}

static uint32_t android_usb_remaining_time(esp_loader_port_t *port)
{
    android_usb_port_t *p = (android_usb_port_t *)port;
    int64_t remaining = (int64_t)(p->timer_end_us - get_time_us()) / 1000;
    return (remaining > 0) ? (uint32_t)remaining : 0;
}

static void android_usb_delay_ms(esp_loader_port_t *port, uint32_t ms)
{
    (void)port;
    usleep(ms * 1000);
}

static void android_usb_log(esp_loader_port_t *port, esp_loader_log_level_t level,
                            const char *fmt, va_list args)
{
    (void)port;
    android_LogPriority priority;
    switch (level) {
        case ESP_LOADER_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case ESP_LOADER_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN;  break;
        case ESP_LOADER_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO;  break;
        case ESP_LOADER_LOG_LEVEL_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        default: priority = ANDROID_LOG_VERBOSE; break;
    }
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);
    __android_log_print(priority, TAG, "%s", buf);
}

static void android_usb_log_hex(esp_loader_port_t *port, esp_loader_log_level_t level,
                                const char *label, const uint8_t *data, size_t size)
{
    (void)port;
    if (level > ESP_LOADER_LOG_LEVEL_DEBUG) {
        return;
    }
    if (label != NULL) {
        LOGD("%s (%zu bytes)", label, size);
    }
    /* Keep hex dumps short. */
    size_t print_len = size > 64 ? 64 : size;
    char hex[193];
    size_t pos = 0;
    for (size_t i = 0; i < print_len && pos < sizeof(hex) - 4; i++) {
        pos += snprintf(hex + pos, sizeof(hex) - pos, "%02x ", data[i]);
    }
    LOGD("%s", hex);
}

static esp_loader_error_t android_usb_change_rate(esp_loader_port_t *port, uint32_t rate)
{
    (void)port;
    (void)rate;
    /* USB CDC ACM full-speed is fixed; no baud-rate change needed. */
    return ESP_LOADER_SUCCESS;
}

/* endpoint: 0 = IN (device -> host), 1 = OUT (host -> device) */
static int call_bulk_transfer(android_usb_port_t *p, int endpoint,
                              uint8_t *data, uint16_t size, uint32_t timeout_ms)
{
    JNIEnv *env = get_jni_env();
    if (env == NULL) {
        LOGE("No JNI env");
        return -1;
    }

    jbyteArray arr = (*env)->NewByteArray(env, size);
    if (arr == NULL) {
        LOGE("Failed to allocate byte array");
        return -1;
    }

    if (endpoint == 1 && data != NULL) {
        (*env)->SetByteArrayRegion(env, arr, 0, size, (const jbyte *)data);
    }

    jint transferred = (*env)->CallIntMethod(
        env, p->helper, g_bulk_transfer_method,
        endpoint, arr, (jint)size, (jint)timeout_ms);

    if (endpoint == 0 && transferred > 0 && data != NULL) {
        (*env)->GetByteArrayRegion(env, arr, 0, transferred, (jbyte *)data);
    }

    (*env)->DeleteLocalRef(env, arr);
    return transferred;
}

static esp_loader_error_t android_usb_write(esp_loader_port_t *port, const uint8_t *data,
                                            const uint16_t size, const uint32_t timeout)
{
    android_usb_port_t *p = (android_usb_port_t *)port;
    if (p->helper == NULL) {
        return ESP_LOADER_ERROR_FAIL;
    }

    int transferred = call_bulk_transfer(p, 1, (uint8_t *)data, size, timeout);
    if (transferred == size) {
        return ESP_LOADER_SUCCESS;
    } else if (transferred < 0) {
        LOGE("USB bulk write failed: %d", transferred);
        return ESP_LOADER_ERROR_FAIL;
    } else {
        LOGE("USB bulk write short: %d/%u", transferred, size);
        return ESP_LOADER_ERROR_TIMEOUT;
    }
}

static esp_loader_error_t android_usb_read(esp_loader_port_t *port, uint8_t *data,
                                           const uint16_t size, const uint32_t timeout)
{
    android_usb_port_t *p = (android_usb_port_t *)port;
    if (p->helper == NULL) {
        return ESP_LOADER_ERROR_FAIL;
    }

    uint64_t deadline_us = get_time_us() + (uint64_t)timeout * 1000ULL;
    uint16_t received = 0;

    while (received < size) {
        uint32_t remaining_ms = (uint32_t)((deadline_us - get_time_us()) / 1000);
        if ((int64_t)(deadline_us - get_time_us()) <= 0) {
            return ESP_LOADER_ERROR_TIMEOUT;
        }
        if (remaining_ms == 0) {
            remaining_ms = 1;
        }

        uint16_t chunk = size - received;
        if (chunk > p->max_packet_size) {
            chunk = p->max_packet_size;
        }

        int r = call_bulk_transfer(p, 0, data + received, chunk, remaining_ms);
        if (r < 0) {
            LOGE("USB bulk read failed: %d", r);
            return ESP_LOADER_ERROR_FAIL;
        }
        if (r == 0) {
            /* Timeout with no data. */
            if (get_time_us() >= deadline_us) {
                return ESP_LOADER_ERROR_TIMEOUT;
            }
            continue;
        }
        received += (uint16_t)r;
    }

    return ESP_LOADER_SUCCESS;
}

static const esp_loader_port_ops_t android_usb_cdc_acm_ops = {
    .init                     = android_usb_port_init,
    .deinit                   = android_usb_port_deinit,
    .enter_bootloader         = android_usb_enter_bootloader,
    .reset_target             = android_usb_reset_target,
    .start_timer              = android_usb_start_timer,
    .remaining_time           = android_usb_remaining_time,
    .delay_ms                 = android_usb_delay_ms,
    .log                      = android_usb_log,
    .log_hex                  = android_usb_log_hex,
    .change_transmission_rate = android_usb_change_rate,
    .write                    = android_usb_write,
    .read                     = android_usb_read,
};

/* Called from JNI to attach a port to a Java Esp32Flasher instance. */
android_usb_port_t *android_usb_port_create(JNIEnv *env, jobject helper,
                                            int in_ep, int out_ep, int max_pkt)
{
    android_usb_port_t *p = calloc(1, sizeof(android_usb_port_t));
    if (p == NULL) {
        return NULL;
    }
    p->base.ops = &android_usb_cdc_acm_ops;
    p->helper = (*env)->NewGlobalRef(env, helper);
    p->in_ep_addr = in_ep;
    p->out_ep_addr = out_ep;
    p->max_packet_size = max_pkt;
    return p;
}

void android_usb_port_destroy(android_usb_port_t *p)
{
    if (p == NULL) {
        return;
    }
    JNIEnv *env = get_jni_env();
    if (env != NULL && p->helper != NULL) {
        (*env)->DeleteGlobalRef(env, p->helper);
    }
    free(p);
}

esp_loader_port_t *android_usb_port_get_base(android_usb_port_t *p)
{
    if (p == NULL) {
        return NULL;
    }
    return &p->base;
}
