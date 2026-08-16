#pragma once

#include <jni.h>
#include "esp_loader.h"
#include "esp_loader_io.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct android_usb_port_s android_usb_port_t;

int android_usb_port_jni_init(JavaVM *vm, JNIEnv *env, jclass helper_class);

android_usb_port_t *android_usb_port_create(JNIEnv *env, jobject helper,
                                            int in_ep, int out_ep, int max_pkt);

void android_usb_port_destroy(android_usb_port_t *p);

esp_loader_port_t *android_usb_port_get_base(android_usb_port_t *p);

#ifdef __cplusplus
}
#endif
