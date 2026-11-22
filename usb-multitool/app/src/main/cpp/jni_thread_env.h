#pragma once

#include <jni.h>

namespace usb_jni {

struct ThreadDetacher {
	JavaVM *jvm = nullptr;
	~ThreadDetacher()
	{
		if (jvm) jvm->DetachCurrentThread();
	}
};

extern thread_local ThreadDetacher tls_detacher;

/* Returns the JNI env for this thread, attaching native threads once. */
JNIEnv *env_for(JavaVM *jvm);

} // namespace usb_jni
