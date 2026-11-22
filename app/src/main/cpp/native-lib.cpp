#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_usb_multitool_NativeBridge_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF("0.1.0");
}
