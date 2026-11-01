#include <jni.h>
#include <stdint.h>
#include <string.h>

/*
 * Ventoy JNI boundary.
 *
 * Upstream Ventoy installer sources are fetched into third_party/ventoy at the
 * pinned release (see third_party/ventoy/README.md). This file only exposes the
 * JNI entry points and refuses 4Kn when the selected release does not support it.
 * Layout constants stay inside upstream code; do not reimplement them here.
 */

#ifndef USB_WITH_VENTOY
#define USB_WITH_VENTOY 0
#endif

JNIEXPORT jboolean JNICALL
Java_usb_ventoy_VentoyNative_nativeEngineLinked(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if USB_WITH_VENTOY
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_usb_ventoy_VentoyNative_nativeInstall(JNIEnv *env, jclass clazz,
                                          jint sectorSize, jlong sectorCount,
                                          jstring release, jint gpt) {
    (void)clazz;
    (void)sectorCount;
    (void)gpt;
    if (sectorSize == 4096) {
        /* Upstream refusal path when release lacks 4Kn support. */
        return -2;
    }
#if USB_WITH_VENTOY
    const char *rel = (*env)->GetStringUTFChars(env, release, NULL);
    /* Call adapted ventoy_install(disk_ops, rel, gpt). */
    (*env)->ReleaseStringUTFChars(env, release, rel);
    return 0;
#else
    (void)env;
    (void)release;
    return -1; /* engines not linked; Kotlin boundary may mark test disks */
#endif
}

JNIEXPORT jint JNICALL
Java_usb_ventoy_VentoyNative_nativeUpdate(JNIEnv *env, jclass clazz,
                                         jint sectorSize, jlong sectorCount,
                                         jstring release) {
    (void)clazz;
    (void)sectorCount;
    if (sectorSize == 4096) {
        return -2;
    }
#if USB_WITH_VENTOY
    const char *rel = (*env)->GetStringUTFChars(env, release, NULL);
    /* Call adapted ventoy_update(disk_ops, rel). */
    (*env)->ReleaseStringUTFChars(env, release, rel);
    return 0;
#else
    (void)env;
    (void)release;
    return -1;
#endif
}
