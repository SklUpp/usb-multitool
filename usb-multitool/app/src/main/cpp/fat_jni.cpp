#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "ff.h"

#include "fat_engine.h"
#include "usb_block.h"

namespace {

struct JniBlockCtx {
	JavaVM *jvm = nullptr;
	jobject callback = nullptr; /* global ref to BlockCallback */
	jmethodID readMid = nullptr;
	jmethodID writeMid = nullptr;
	jmethodID syncMid = nullptr;
	uint32_t sector_size = 0;
	uint64_t sector_count = 0;
	UsbBlockOps ops{};
};

thread_local JNIEnv *tls_env = nullptr;

JNIEnv *env_for(JavaVM *jvm)
{
	if (tls_env) return tls_env;
	JNIEnv *env = nullptr;
	if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		if (jvm->AttachCurrentThread(&env, nullptr) != 0) return nullptr;
	}
	tls_env = env;
	return env;
}

int jni_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors)
{
	auto *c = static_cast<JniBlockCtx *>(ctx);
	JNIEnv *env = env_for(c->jvm);
	if (!env) return -1;
	const jlong bytes = static_cast<jlong>(sectors) * c->sector_size;
	jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes));
	if (!arr) return -1;
	jint rc = env->CallIntMethod(c->callback, c->readMid, static_cast<jlong>(lba), arr);
	if (env->ExceptionCheck() || rc != 0) {
		env->ExceptionClear();
		env->DeleteLocalRef(arr);
		return -1;
	}
	env->GetByteArrayRegion(arr, 0, static_cast<jsize>(bytes), reinterpret_cast<jbyte *>(buf));
	env->DeleteLocalRef(arr);
	return 0;
}

int jni_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
	auto *c = static_cast<JniBlockCtx *>(ctx);
	JNIEnv *env = env_for(c->jvm);
	if (!env) return -1;
	const jlong bytes = static_cast<jlong>(sectors) * c->sector_size;
	jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes));
	if (!arr) return -1;
	env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes),
				reinterpret_cast<const jbyte *>(buf));
	jint rc = env->CallIntMethod(c->callback, c->writeMid, static_cast<jlong>(lba), arr);
	env->DeleteLocalRef(arr);
	if (env->ExceptionCheck() || rc != 0) {
		env->ExceptionClear();
		return -1;
	}
	return 0;
}

int jni_sync(void *ctx)
{
	auto *c = static_cast<JniBlockCtx *>(ctx);
	JNIEnv *env = env_for(c->jvm);
	if (!env) return -1;
	jint rc = env->CallIntMethod(c->callback, c->syncMid);
	if (env->ExceptionCheck() || rc != 0) {
		env->ExceptionClear();
		return -1;
	}
	return 0;
}

jstring to_jstring(JNIEnv *env, const char *s)
{
	if (!s) return env->NewString(nullptr, 0);
	std::vector<jchar> u16;
	const auto *p = reinterpret_cast<const uint8_t *>(s);
	while (*p) {
		uint32_t cp = 0;
		if (*p < 0x80) {
			cp = *p++;
		} else if ((*p & 0xE0) == 0xC0) {
			if ((p[1] & 0xC0) != 0x80) { p++; continue; }
			cp = ((p[0] & 0x1F) << 6) | (p[1] & 0x3F);
			p += 2;
		} else if ((*p & 0xF0) == 0xE0) {
			if ((p[1] & 0xC0) != 0x80 || (p[2] & 0xC0) != 0x80) { p++; continue; }
			cp = ((p[0] & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
			p += 3;
		} else if ((*p & 0xF8) == 0xF0) {
			if ((p[1] & 0xC0) != 0x80 || (p[2] & 0xC0) != 0x80 || (p[3] & 0xC0) != 0x80) { p++; continue; }
			cp = ((p[0] & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
			p += 4;
		} else {
			p++;
			continue;
		}
		if (cp <= 0xFFFF) {
			u16.push_back(static_cast<jchar>(cp));
		} else if (cp <= 0x10FFFF) {
			cp -= 0x10000;
			u16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
			u16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
		}
	}
	return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

std::string from_jstring(JNIEnv *env, jstring s)
{
	if (!s) return {};
	jsize len = env->GetStringLength(s);
	const jchar *chars = env->GetStringChars(s, nullptr);
	if (!chars) return {};
	std::string out;
	for (jsize i = 0; i < len; i++) {
		uint32_t cp = chars[i];
		if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
			uint32_t low = chars[i + 1];
			if (low >= 0xDC00 && low <= 0xDFFF) {
				cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
				i++;
			}
		}
		if (cp < 0x80) {
			out.push_back(static_cast<char>(cp));
		} else if (cp < 0x800) {
			out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
			out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
		} else if (cp < 0x10000) {
			out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
			out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
			out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
		} else {
			out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
			out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
			out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
			out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
		}
	}
	env->ReleaseStringChars(s, chars);
	return out;
}

void throw_fat(JNIEnv *env, const char *cls, const char *msg)
{
	jclass c = env->FindClass(cls);
	if (c) env->ThrowNew(c, msg);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_usb_fs_FatFsNative_nativeOpenBlock(JNIEnv *env, jclass, jobject callback,
					jint sectorSize, jlong sectorCount)
{
	if (!callback || sectorSize < 512 || sectorSize > 4096 || sectorCount <= 0) return 0;
	JavaVM *jvm = nullptr;
	env->GetJavaVM(&jvm);
	jclass cls = env->GetObjectClass(callback);
	jmethodID readMid = env->GetMethodID(cls, "readSectors", "(J[B)I");
	jmethodID writeMid = env->GetMethodID(cls, "writeSectors", "(J[B)I");
	jmethodID syncMid = env->GetMethodID(cls, "sync", "()I");
	if (!readMid || !writeMid || !syncMid) return 0;

	auto *ctx = new JniBlockCtx();
	ctx->jvm = jvm;
	ctx->callback = env->NewGlobalRef(callback);
	ctx->readMid = readMid;
	ctx->writeMid = writeMid;
	ctx->syncMid = syncMid;
	ctx->sector_size = static_cast<uint32_t>(sectorSize);
	ctx->sector_count = static_cast<uint64_t>(sectorCount);
	ctx->ops.read = jni_read;
	ctx->ops.write = jni_write;
	ctx->ops.sync = jni_sync;
	ctx->ops.ctx = ctx;
	ctx->ops.sector_size = ctx->sector_size;
	ctx->ops.sector_count = ctx->sector_count;
	return static_cast<jlong>(usb_block_register(&ctx->ops));
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeCloseBlock(JNIEnv *env, jclass, jlong handle)
{
	UsbBlockOps *ops = usb_block_ops(static_cast<uint64_t>(handle));
	if (!ops) return;
	auto *ctx = static_cast<JniBlockCtx *>(ops->ctx);
	if (ctx) {
		if (ctx->callback) env->DeleteGlobalRef(ctx->callback);
		delete ctx;
	}
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_fs_FatFsNative_nativeMount(JNIEnv *env, jclass, jlong blockHandle, jint sectorSize,
				    jlong sectorCount, jintArray outKind)
{
	FatFsKind kind = FAT_KIND_UNKNOWN;
	uint64_t vol = 0;
	int rc = fat_engine_mount(static_cast<uint64_t>(blockHandle),
				  static_cast<uint32_t>(sectorSize),
				  static_cast<uint64_t>(sectorCount), &kind, &vol);
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException", ("mount failed: " + std::to_string(rc)).c_str());
		return 0;
	}
	if (outKind) {
		jint k = static_cast<jint>(kind);
		env->SetIntArrayRegion(outKind, 0, 1, &k);
	}
	return static_cast<jlong>(vol);
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_fs_FatFsNative_nativeUmount(JNIEnv *env, jclass, jlong volume)
{
	int rc = fat_engine_umount(static_cast<uint64_t>(volume));
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException",
			  ("umount failed: " + std::to_string(rc)).c_str());
	}
	return static_cast<jint>(rc);
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeSync(JNIEnv *env, jclass, jlong volume)
{
	int rc = fat_engine_sync(static_cast<uint64_t>(volume));
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException",
			  ("sync failed: " + std::to_string(rc)).c_str());
	}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_usb_fs_FatFsNative_nativeList(JNIEnv *env, jclass, jlong volume, jstring path)
{
	std::string p = from_jstring(env, path);
	size_t count = 0;
	int rc = fat_engine_list(static_cast<uint64_t>(volume), p.c_str(), nullptr, 0, &count);
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException", ("list failed: " + std::to_string(rc)).c_str());
		return nullptr;
	}
	std::vector<FatDirEnt> tmp(count);
	if (count > 0) {
		rc = fat_engine_list(static_cast<uint64_t>(volume), p.c_str(), tmp.data(), tmp.size(), &count);
		if (rc != 0) {
			throw_fat(env, "usb/fs/FatFsException", ("list failed: " + std::to_string(rc)).c_str());
			return nullptr;
		}
	}
	if (count > tmp.size()) {
		count = tmp.size();
	}
	jclass entryCls = env->FindClass("usb/fs/FatDirEntry");
	jmethodID ctor = env->GetMethodID(entryCls, "<init>", "(Ljava/lang/String;ZJB)V");
	jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), entryCls, nullptr);
	for (size_t i = 0; i < count && i < tmp.size(); i++) {
		jstring name = to_jstring(env, tmp[i].name);
		jobject obj = env->NewObject(entryCls, ctor, name, tmp[i].is_dir ? JNI_TRUE : JNI_FALSE,
					     static_cast<jlong>(tmp[i].size),
					     static_cast<jbyte>(tmp[i].attrib));
		env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);
		env->DeleteLocalRef(obj);
		env->DeleteLocalRef(name);
	}
	return arr;
}


extern "C" JNIEXPORT jobject JNICALL
Java_usb_fs_FatFsNative_nativeStat(JNIEnv *env, jclass, jlong volume, jstring path)
{
	std::string p = from_jstring(env, path);
	FatStat st{};
	int rc = fat_engine_stat(static_cast<uint64_t>(volume), p.c_str(), &st);
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException", ("stat failed: " + std::to_string(rc)).c_str());
		return nullptr;
	}
	jclass entryCls = env->FindClass("usb/fs/FatDirEntry");
	jmethodID ctor = env->GetMethodID(entryCls, "<init>", "(Ljava/lang/String;ZJB)V");
	jstring name = to_jstring(env, st.name);
	return env->NewObject(entryCls, ctor, name, st.is_dir ? JNI_TRUE : JNI_FALSE,
			      static_cast<jlong>(st.size), static_cast<jbyte>(st.attrib));
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeMkdir(JNIEnv *env, jclass, jlong volume, jstring path)
{
	std::string p = from_jstring(env, path);
	int rc = fat_engine_mkdir(static_cast<uint64_t>(volume), p.c_str());
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("mkdir failed: " + std::to_string(rc)).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeDelete(JNIEnv *env, jclass, jlong volume, jstring path)
{
	std::string p = from_jstring(env, path);
	int rc = fat_engine_delete(static_cast<uint64_t>(volume), p.c_str());
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("delete failed: " + std::to_string(rc)).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeRename(JNIEnv *env, jclass, jlong volume, jstring from, jstring to)
{
	std::string a = from_jstring(env, from);
	std::string b = from_jstring(env, to);
	int rc = fat_engine_rename(static_cast<uint64_t>(volume), a.c_str(), b.c_str());
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("rename failed: " + std::to_string(rc)).c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_usb_fs_FatFsNative_nativeRead(JNIEnv *env, jclass, jlong volume, jstring path, jlong offset,
				   jint length)
{
	std::string p = from_jstring(env, path);
	if (length < 0) {
		throw_fat(env, "usb/fs/FatFsException", "negative length");
		return nullptr;
	}
	std::vector<uint8_t> buf(static_cast<size_t>(length));
	size_t got = 0;
	int rc = fat_engine_read_file(static_cast<uint64_t>(volume), p.c_str(),
				      static_cast<uint64_t>(offset), buf.data(), buf.size(), &got);
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException", ("read failed: " + std::to_string(rc)).c_str());
		return nullptr;
	}
	jbyteArray out = env->NewByteArray(static_cast<jsize>(got));
	if (got > 0) {
		env->SetByteArrayRegion(out, 0, static_cast<jsize>(got),
					reinterpret_cast<const jbyte *>(buf.data()));
	}
	return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_fs_FatFsNative_nativeWrite(JNIEnv *env, jclass, jlong volume, jstring path, jlong offset,
					    jbyteArray data, jlong plannedTotalSize, jboolean create,
					    jboolean exclusiveCreate)
{
	std::string p = from_jstring(env, path);
	if (!data) {
		throw_fat(env, "usb/fs/FatFsException", "null data");
		return -1;
	}
	jsize len = env->GetArrayLength(data);
	std::vector<uint8_t> buf(static_cast<size_t>(len));
	if (len > 0) {
		env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buf.data()));
	}
	size_t wrote = 0;
	int createMode = create ? (exclusiveCreate ? 2 : 1) : 0;
	int rc = fat_engine_write_file(static_cast<uint64_t>(volume), p.c_str(),
				       static_cast<uint64_t>(offset), buf.data(), buf.size(), &wrote,
				       static_cast<uint64_t>(plannedTotalSize), createMode);
    if (rc == FAT_ENGINE_CLEANUP_FAILED) {
        throw_fat(env, "usb/fs/FatFsException",
                  ("exclusive create cleanup failed; temporary file may remain: " + p).c_str());
        return -1;
    }
    if (rc == 8 && exclusiveCreate) {
        throw_fat(env, "usb/fs/FileExistsException",
                  ("exclusive create failed, already exists: " + p).c_str());
        return -1;
    }
	if (rc == FAT_ENGINE_FILE_TOO_LARGE) {
		throw_fat(env, "usb/fs/FileTooLargeException",
			  "file exceeds filesystem size limit");
		return -1;
	}
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException", ("write failed: " + std::to_string(rc)).c_str());
		return -1;
	}
	return static_cast<jint>(wrote);
}


extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeMkfs(JNIEnv *env, jclass, jlong blockHandle, jint sectorSize,
				   jlong sectorCount, jint type, jint auSizeBytes)
{
	int rc = fat_engine_mkfs(static_cast<uint64_t>(blockHandle),
				 static_cast<uint32_t>(sectorSize),
				 static_cast<uint64_t>(sectorCount),
				 static_cast<FatMkfsType>(type),
				 static_cast<uint32_t>(auSizeBytes > 0 ? auSizeBytes : 0));
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("mkfs failed: " + std::to_string(rc)).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_usb_fs_FatFsNative_nativeGetLabel(JNIEnv *env, jclass, jlong blockHandle, jint sectorSize,
				       jlong sectorCount)
{
	char label[128];
	int rc = fat_engine_get_label(static_cast<uint64_t>(blockHandle),
				      static_cast<uint32_t>(sectorSize),
				      static_cast<uint64_t>(sectorCount), label, sizeof(label));
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("get label failed: " + std::to_string(rc)).c_str());
	return to_jstring(env, label);
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeSetLabel(JNIEnv *env, jclass, jlong blockHandle, jint sectorSize,
				       jlong sectorCount, jstring label)
{
	std::string text = from_jstring(env, label);
	int rc = fat_engine_set_label(static_cast<uint64_t>(blockHandle),
				      static_cast<uint32_t>(sectorSize),
				      static_cast<uint64_t>(sectorCount), text.c_str());
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("set label failed: " + std::to_string(rc)).c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_fs_FatFsNative_nativeProbe(JNIEnv *, jclass, jlong blockHandle, jint sectorSize,
				    jlong sectorCount)
{
	return static_cast<jint>(fat_engine_probe(static_cast<uint64_t>(blockHandle),
						  static_cast<uint32_t>(sectorSize),
						  static_cast<uint64_t>(sectorCount)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_fs_FatFsNative_nativeMaxFileSize(JNIEnv *, jclass, jint kind)
{
	return static_cast<jlong>(fat_engine_max_file_size(static_cast<FatFsKind>(kind)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_usb_fs_FatFsNative_nativeUsage(JNIEnv *env, jclass, jlong volume, jlongArray out)
{
	if (!out || env->GetArrayLength(out) < 2) {
		throw_fat(env, "usb/fs/FatFsException", "usage: output array too small");
		return JNI_FALSE;
	}
	uint64_t total = 0;
	uint64_t free_bytes = 0;
	int rc = fat_engine_usage(static_cast<uint64_t>(volume), &total, &free_bytes);
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException",
			  ("space query failed: " + std::to_string(rc)).c_str());
		return JNI_FALSE;
	}
	jlong values[2] = { static_cast<jlong>(total), static_cast<jlong>(free_bytes) };
	env->SetLongArrayRegion(out, 0, 2, values);
	return JNI_TRUE;
}
