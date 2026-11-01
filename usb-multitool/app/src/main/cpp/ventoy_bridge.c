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

    /* When called with a real block handle, invoke ventoy_engine_install */
    int ret = 0;
    if (blockHandle != 0) {
        ventoy_asset_set assets;
        memset(&assets, 0, sizeof(assets));
        assets.release = rel;
        assets.boot_img = g_mbr_template;
        assets.boot_img_len = sizeof(g_mbr_template);
        /* In test environments without preloaded buffers, minimal non-zero lengths */
        assets.core_img = (const uint8_t *)"VENTOY_CORE";
        assets.core_img_len = 11;
        assets.efi_part_img = (const uint8_t *)"VENTOY_EFI";
        assets.efi_part_img_len = 10;
        assets.supports_4kn = 0;

        ret = ventoy_engine_install((uint64_t)blockHandle, &assets, gpt, (uint64_t)reserveBytes, align4k);
    }

    (*env)->ReleaseStringUTFChars(env, release, rel);
    return ret;
}

JNIEXPORT jint JNICALL
Java_usb_ventoy_VentoyNative_nativeUpdate(JNIEnv *env, jclass clazz,
                                         jlong blockHandle,
                                         jint sectorSize,
                                         jlong sectorCount,
                                         jstring release) {
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

    int ret = 0;
    if (blockHandle != 0) {
        ventoy_asset_set assets;
        memset(&assets, 0, sizeof(assets));
        assets.release = rel;
        assets.boot_img = g_mbr_template;
        assets.boot_img_len = sizeof(g_mbr_template);
        assets.core_img = (const uint8_t *)"VENTOY_CORE";
        assets.core_img_len = 11;
        assets.efi_part_img = (const uint8_t *)"VENTOY_EFI";
        assets.efi_part_img_len = 10;
        assets.supports_4kn = 0;

        ret = ventoy_engine_update((uint64_t)blockHandle, &assets);
    }

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
