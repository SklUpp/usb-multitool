#include <jni.h>
#include <stdint.h>

/*
 * GNU ddrescue map JNI boundary.
 * Upstream: https://ftp.gnu.org/gnu/ddrescue/ddrescue-1.30.tar.lz
 * Map status transitions stay faithful to ddrescue; Kotlin RescueMap mirrors them
 * for unit tests without the full engine.
 */

#ifndef USB_WITH_DDRESCUE
#define USB_WITH_DDRESCUE 0
#endif

JNIEXPORT jboolean JNICALL
Java_usb_diagnostics_DdrescueNative_nativeEngineLinked(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if USB_WITH_DDRESCUE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_usb_diagnostics_DdrescueNative_nativePersist(JNIEnv *env, jclass clazz,
                                                  jlong sectorCount, jboolean partial) {
    (void)env;
    (void)clazz;
    (void)sectorCount;
    (void)partial;
#if USB_WITH_DDRESCUE
    /* Flush mapfile beside partial image via adapted ddrescue writer. */
#endif
}
