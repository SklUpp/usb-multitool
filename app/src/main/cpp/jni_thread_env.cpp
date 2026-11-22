#include "jni_thread_env.h"

namespace usb_jni {

thread_local ThreadDetacher tls_detacher;

JNIEnv *env_for(JavaVM *jvm)
{
	JNIEnv *env = nullptr;
	jint status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
	if (status == JNI_OK) return env;
	if (status != JNI_EDETACHED) return nullptr;
	if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
	/* Attached native threads must detach before they exit, or ART aborts. */
	tls_detacher.jvm = jvm;
	return env;
}

} // namespace usb_jni
