#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>

#include "ventoy_engine.h"
#include "usb_block.h"

JNIEXPORT jboolean JNICALL
Java_usb_ventoy_VentoyNative_nativeEngineLinked(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_usb_ventoy_VentoyNative_nativeInstall(JNIEnv *env, jclass clazz,
                                          jlong blockHandle,
                                          jint sectorSize,
                                          jlong sectorCount,
                                          jstring release,
                                          jbyteArray bootImg,
                                          jbyteArray coreImg,
                                          jbyteArray efiPartImg,
                                          jint gpt,
                                          jlong reserveBytes,
                                          jint align4k) {
    (void)clazz;
    (void)sectorCount;

    if (sectorSize == 4096) {
        /* Upstream Ventoy does not support 4K native devices */
        return -ENOTSUP;
    }

    const char *rel = (*env)->GetStringUTFChars(env, release, NULL);
    if (!rel || strcmp(rel, VENTOY_PINNED_RELEASE) != 0) {
        if (rel) (*env)->ReleaseStringUTFChars(env, release, rel);
        return -EINVAL;
    }

    if (!bootImg || !coreImg || !efiPartImg) {
        (*env)->ReleaseStringUTFChars(env, release, rel);
        return -EINVAL;
    }

    jsize boot_len = (*env)->GetArrayLength(env, bootImg);
    jsize core_len = (*env)->GetArrayLength(env, coreImg);
    jsize efi_len = (*env)->GetArrayLength(env, efiPartImg);

    if (boot_len < 446 || core_len == 0 || efi_len == 0) {
        (*env)->ReleaseStringUTFChars(env, release, rel);
        return -EINVAL;
    }

    jbyte *boot_bytes = (*env)->GetByteArrayElements(env, bootImg, NULL);
    jbyte *core_bytes = (*env)->GetByteArrayElements(env, coreImg, NULL);
    jbyte *efi_bytes = (*env)->GetByteArrayElements(env, efiPartImg, NULL);

    int ret = 0;
    if (blockHandle != 0) {
        ventoy_asset_set assets;
        memset(&assets, 0, sizeof(assets));
        assets.release = rel;
        assets.boot_img = (const uint8_t *)boot_bytes;
        assets.boot_img_len = (size_t)boot_len;
        assets.core_img = (const uint8_t *)core_bytes;
        assets.core_img_len = (size_t)core_len;
        assets.efi_part_img = (const uint8_t *)efi_bytes;
        assets.efi_part_img_len = (size_t)efi_len;
        assets.supports_4kn = 0;

        ret = ventoy_engine_install((uint64_t)blockHandle, &assets, gpt, (uint64_t)reserveBytes, align4k);
    }

    (*env)->ReleaseByteArrayElements(env, efiPartImg, efi_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, coreImg, core_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, bootImg, boot_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, release, rel);
    return ret;
}

JNIEXPORT jint JNICALL
Java_usb_ventoy_VentoyNative_nativeUpdate(JNIEnv *env, jclass clazz,
                                         jlong blockHandle,
                                         jint sectorSize,
                                         jlong sectorCount,
                                         jstring release,
                                         jbyteArray bootImg,
                                         jbyteArray coreImg,
                                         jbyteArray efiPartImg) {
    (void)clazz;
    (void)sectorCount;

    if (sectorSize == 4096) {
        return -ENOTSUP;
    }

    const char *rel = (*env)->GetStringUTFChars(env, release, NULL);
    if (!rel || strcmp(rel, VENTOY_PINNED_RELEASE) != 0) {
        if (rel) (*env)->ReleaseStringUTFChars(env, release, rel);
        return -EINVAL;
    }

    if (!bootImg || !coreImg || !efiPartImg) {
        (*env)->ReleaseStringUTFChars(env, release, rel);
        return -EINVAL;
    }

    jsize boot_len = (*env)->GetArrayLength(env, bootImg);
    jsize core_len = (*env)->GetArrayLength(env, coreImg);
    jsize efi_len = (*env)->GetArrayLength(env, efiPartImg);

    if (boot_len < 446 || core_len == 0 || efi_len == 0) {
        (*env)->ReleaseStringUTFChars(env, release, rel);
        return -EINVAL;
    }

    jbyte *boot_bytes = (*env)->GetByteArrayElements(env, bootImg, NULL);
    jbyte *core_bytes = (*env)->GetByteArrayElements(env, coreImg, NULL);
    jbyte *efi_bytes = (*env)->GetByteArrayElements(env, efiPartImg, NULL);

    int ret = 0;
    if (blockHandle != 0) {
        ventoy_asset_set assets;
        memset(&assets, 0, sizeof(assets));
        assets.release = rel;
        assets.boot_img = (const uint8_t *)boot_bytes;
        assets.boot_img_len = (size_t)boot_len;
        assets.core_img = (const uint8_t *)core_bytes;
        assets.core_img_len = (size_t)core_len;
        assets.efi_part_img = (const uint8_t *)efi_bytes;
        assets.efi_part_img_len = (size_t)efi_len;
        assets.supports_4kn = 0;

        ret = ventoy_engine_update((uint64_t)blockHandle, &assets);
    }

    (*env)->ReleaseByteArrayElements(env, efiPartImg, efi_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, coreImg, core_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, bootImg, boot_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, release, rel);
    return ret;
}

JNIEXPORT jint JNICALL
Java_usb_ventoy_VentoyNative_nativeProbe(JNIEnv *env, jclass clazz,
                                        jlong blockHandle,
                                        jint sectorSize,
                                        jlong sectorCount) {
    (void)env;
    (void)clazz;
    (void)sectorCount;

    if (sectorSize == 4096) {
        return -ENOTSUP;
    }
    if (blockHandle == 0) {
        return -EINVAL;
    }

    ventoy_disk_probe probe;
    int rc = ventoy_engine_probe((uint64_t)blockHandle, &probe);
    if (rc != 0) return rc;
    return probe.is_ventoy ? 1 : 0;
}
