/*
 * JNI bridge for AprilTag3 16h5 detection.
 *
 * Exposes a single native method used by AprilTagDetector:
 *   AprilTagDetector.nativeDetect(Bitmap bitmap)
 * which returns an array of AprilTagDetector.Detection objects.
 *
 * The detector is created once per process and configured for the tag16h5
 * family. Detection runs on the calling thread.
 */

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "apriltag/apriltag.h"
#include "apriltag/tag16h5.h"

#define TAG "AprilTagJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static apriltag_detector_t *s_detector = NULL;
static apriltag_family_t *s_tag_family = NULL;
static jclass s_detection_class = NULL;
static jmethodID s_detection_ctor = NULL;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;

    JNIEnv *env = NULL;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass cls = env->FindClass("com/example/remotesupportheadset/AprilTagDetector$Detection");
    if (cls == NULL) {
        LOGE("AprilTagDetector.Detection class not found");
        return JNI_ERR;
    }
    s_detection_class = (jclass)env->NewGlobalRef(cls);

    s_detection_ctor = env->GetMethodID(s_detection_class, "<init>",
        "(IFFFFFFFF)V");
    if (s_detection_ctor == NULL) {
        LOGE("AprilTagDetector.Detection ctor not found");
        return JNI_ERR;
    }

    s_detector = apriltag_detector_create();
    if (s_detector == NULL) {
        LOGE("apriltag_detector_create failed");
        return JNI_ERR;
    }

    // Tune for reasonable speed/accuracy on Android still images.
    s_detector->quad_decimate = 2.0f;
    s_detector->quad_sigma = 0.0f;
    s_detector->nthreads = 2;
    s_detector->refine_edges = 1;
    s_detector->decode_sharpening = 0.25;

    s_tag_family = tag16h5_create();
    if (s_tag_family == NULL) {
        LOGE("tag16h5_create failed");
        return JNI_ERR;
    }

    apriltag_detector_add_family(s_detector, s_tag_family);

    LOGI("AprilTag detector loaded (16h5)");
    return JNI_VERSION_1_6;
}

static void unlock_pixels(JNIEnv *env, jobject bitmap, void *pixels)
{
    if (bitmap != NULL && pixels != NULL) {
        AndroidBitmap_unlockPixels(env, bitmap);
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_remotesupportheadset_AprilTagDetector_nativeDetect(
    JNIEnv *env,
    jobject thiz,
    jobject bitmap)
{
    (void)thiz;

    if (s_detector == NULL || s_detection_class == NULL || s_detection_ctor == NULL) {
        LOGE("Native detector not initialized");
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    if (bitmap == NULL) {
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 &&
        info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        LOGE("Unsupported bitmap format: %d", info.format);
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed");
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    image_u8_t *im = image_u8_create((int32_t)info.width, (int32_t)info.height);
    if (im == NULL) {
        unlock_pixels(env, bitmap, pixels);
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    const uint8_t *src = (const uint8_t *)pixels;
    const size_t src_stride = info.stride;

    for (uint32_t y = 0; y < info.height; ++y) {
        uint8_t *dst_row = im->buf + y * im->stride;
        if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            const uint8_t *src_row = src + y * src_stride;
            for (uint32_t x = 0; x < info.width; ++x) {
                const uint8_t r = src_row[x * 4 + 0];
                const uint8_t g = src_row[x * 4 + 1];
                const uint8_t b = src_row[x * 4 + 2];
                // Fast luminance approximation.
                dst_row[x] = (uint8_t)((76 * r + 150 * g + 29 * b) >> 8);
            }
        } else { // RGB_565
            const uint16_t *src_row = (const uint16_t *)(src + y * src_stride);
            for (uint32_t x = 0; x < info.width; ++x) {
                uint16_t p = src_row[x];
                uint8_t r = (uint8_t)(((p >> 11) & 0x1F) << 3);
                uint8_t g = (uint8_t)(((p >> 5) & 0x3F) << 2);
                uint8_t b = (uint8_t)((p & 0x1F) << 3);
                dst_row[x] = (uint8_t)((76 * r + 150 * g + 29 * b) >> 8);
            }
        }
    }

    unlock_pixels(env, bitmap, pixels);
    pixels = NULL;

    zarray_t *detections = apriltag_detector_detect(s_detector, im);
    image_u8_destroy(im);

    int count = zarray_size(detections);
    jobjectArray result = env->NewObjectArray(count, s_detection_class, NULL);
    if (result == NULL) {
        apriltag_detections_destroy(detections);
        return env->NewObjectArray(0, s_detection_class, NULL);
    }

    for (int i = 0; i < count; ++i) {
        apriltag_detection_t *det;
        zarray_get(detections, i, &det);

        jobject obj = env->NewObject(s_detection_class, s_detection_ctor,
            (jint)det->id,
            (jfloat)det->p[0][0], (jfloat)det->p[0][1],
            (jfloat)det->p[1][0], (jfloat)det->p[1][1],
            (jfloat)det->p[2][0], (jfloat)det->p[2][1],
            (jfloat)det->p[3][0], (jfloat)det->p[3][1]);

        if (obj != NULL) {
            env->SetObjectArrayElement(result, i, obj);
            env->DeleteLocalRef(obj);
        }
    }

    apriltag_detections_destroy(detections);
    return result;
}
