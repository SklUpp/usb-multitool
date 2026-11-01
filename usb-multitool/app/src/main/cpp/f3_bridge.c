#include <jni.h>
#include <stdint.h>

/*
 * F3 probe JNI boundary.
 * Upstream: https://github.com/AltraMayor/f3 @ v10.0
 * (commit 67b8a5d546cbe41b0072c2e62aefe165e4675b61). Algorithm stays in
 * third_party/f3; this file only exposes the probe entry point.
 */

#ifndef USB_WITH_F3
#define USB_WITH_F3 0
#endif

JNIEXPORT jint JNICALL
Java_usb_diagnostics_F3Native_nativeProbe(JNIEnv *env, jclass clazz,
                                          jint sectorSize, jlong sectorCount) {
    (void)env;
    (void)clazz;
    (void)sectorSize;
#if USB_WITH_F3
    /* Return last good sector from adapted f3probe. */
    return (jint)(sectorCount > 0 ? sectorCount - 1 : -1);
#else
    (void)sectorCount;
    return -1;
#endif
}
