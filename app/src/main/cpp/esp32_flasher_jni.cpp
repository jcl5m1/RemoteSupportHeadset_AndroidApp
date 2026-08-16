/*
 * JNI bridge for esp-serial-flasher.
 *
 * Exposes one high-level native method:
 *   Esp32Flasher.nativeFlash(fd, inEpAddr, outEpAddr, maxPkt, offset, image)
 * which connects to the ESP32-P4 ROM bootloader and writes one binary image
 * to flash. The Kotlin wrapper calls it once per firmware file.
 *
 * During the write loop the native code calls back into
 * Esp32Flasher.onFlashProgress(transferred, total) so the UI can show a
 * byte-accurate progress bar.
 */

#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "android_usb_cdc_port.h"
#include "esp_loader.h"

#define TAG "Esp32Flasher"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *s_vm = NULL;
static jmethodID s_on_flash_progress_mid = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    s_vm = vm;

    JNIEnv *env = NULL;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass helper_class = env->FindClass("com/example/remotesupportheadset/Esp32Flasher");
    if (helper_class == NULL) {
        LOGE("Esp32Flasher class not found");
        return JNI_ERR;
    }

    s_on_flash_progress_mid = env->GetMethodID(helper_class, "onFlashProgress", "(JJ)V");
    if (s_on_flash_progress_mid == NULL) {
        LOGE("Esp32Flasher.onFlashProgress(JJ)V not found");
        return JNI_ERR;
    }

    if (android_usb_port_jni_init(vm, env, helper_class) != 0) {
        LOGE("android_usb_port_jni_init failed");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

static void call_flash_progress(JNIEnv *env, jobject thiz, size_t transferred, size_t total)
{
    if (s_on_flash_progress_mid != NULL) {
        env->CallVoidMethod(thiz, s_on_flash_progress_mid,
                            (jlong)transferred, (jlong)total);
    }
}

static int flash_image(JNIEnv *env, jobject thiz, android_usb_port_t *port,
                       int offset, const uint8_t *image, size_t image_size)
{
    if (image_size == 0 || image == NULL) {
        LOGE("Empty image");
        return -1;
    }

    esp_loader_t loader;
    memset(&loader, 0, sizeof(loader));

    esp_loader_error_t err = esp_loader_init_serial(&loader, android_usb_port_get_base(port));
    if (err != ESP_LOADER_SUCCESS) {
        LOGE("esp_loader_init_serial failed: %d", err);
        return -1;
    }

    esp_loader_connect_args_t connect_args = ESP_LOADER_CONNECT_DEFAULT();
    err = esp_loader_connect(&loader, &connect_args);
    if (err != ESP_LOADER_SUCCESS) {
        LOGE("esp_loader_connect failed: %d", err);
        esp_loader_deinit(&loader);
        return -1;
    }

    LOGI("Connected to target: %d", esp_loader_get_target(&loader));

    esp_loader_flash_cfg_t cfg = {
        .offset     = (uint32_t)offset,
        .image_size = (uint32_t)image_size,
        .block_size = 1024,
        .skip_verify = false,
    };

    err = esp_loader_flash_start(&loader, &cfg);
    if (err != ESP_LOADER_SUCCESS) {
        LOGE("esp_loader_flash_start failed: %d", err);
        esp_loader_deinit(&loader);
        return -1;
    }

    call_flash_progress(env, thiz, 0, image_size);

    size_t written = 0;
    while (written < image_size) {
        size_t chunk = image_size - written;
        if (chunk > cfg.block_size) {
            chunk = cfg.block_size;
        }
        err = esp_loader_flash_write(&loader, &cfg, image + written, (uint32_t)chunk);
        if (err != ESP_LOADER_SUCCESS) {
            LOGE("esp_loader_flash_write failed at offset %zu: %d", written, err);
            esp_loader_deinit(&loader);
            return -1;
        }
        written += chunk;
        call_flash_progress(env, thiz, written, image_size);
    }

    err = esp_loader_flash_finish(&loader, &cfg);
    if (err != ESP_LOADER_SUCCESS) {
        LOGE("esp_loader_flash_finish failed: %d", err);
        esp_loader_deinit(&loader);
        return -1;
    }

    LOGI("Flash complete: %zu bytes at 0x%x", image_size, offset);

    esp_loader_deinit(&loader);

    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_remotesupportheadset_Esp32Flasher_nativeFlash(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jint in_ep_addr,
    jint out_ep_addr,
    jint max_pkt,
    jint offset,
    jbyteArray image)
{
    (void)fd;

    if (image == NULL) {
        LOGE("image is null");
        return -1;
    }

    jsize image_len = env->GetArrayLength(image);
    if (image_len <= 0) {
        LOGE("image is empty");
        return -1;
    }

    jbyte *image_ptr = env->GetByteArrayElements(image, NULL);
    if (image_ptr == NULL) {
        LOGE("GetByteArrayElements failed");
        return -1;
    }

    android_usb_port_t *port = android_usb_port_create(
        env, thiz, in_ep_addr, out_ep_addr, max_pkt > 0 ? max_pkt : 512);
    if (port == NULL) {
        LOGE("android_usb_port_create failed");
        env->ReleaseByteArrayElements(image, image_ptr, JNI_ABORT);
        return -1;
    }

    int ret = flash_image(env, thiz, port, offset,
                          (const uint8_t *)image_ptr, (size_t)image_len);

    android_usb_port_destroy(port);
    env->ReleaseByteArrayElements(image, image_ptr, JNI_ABORT);

    return ret;
}
