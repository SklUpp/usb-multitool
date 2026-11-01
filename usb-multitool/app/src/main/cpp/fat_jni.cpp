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
	return env->NewStringUTF(s ? s : "");
}

std::string from_jstring(JNIEnv *env, jstring s)
{
	if (!s) return {};
	const char *p = env->GetStringUTFChars(s, nullptr);
	std::string out = p ? p : "";
	if (p) env->ReleaseStringUTFChars(s, p);
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

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeUmount(JNIEnv *, jclass, jlong volume)
{
	fat_engine_umount(static_cast<uint64_t>(volume));
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_FatFsNative_nativeSync(JNIEnv *env, jclass, jlong volume)
{
	int rc = fat_engine_sync(static_cast<uint64_t>(volume));
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", "sync failed");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_usb_fs_FatFsNative_nativeList(JNIEnv *env, jclass, jlong volume, jstring path)
{
	std::string p = from_jstring(env, path);
	FatDirEnt tmp[256];
	size_t count = 0;
	int rc = fat_engine_list(static_cast<uint64_t>(volume), p.c_str(), tmp, 256, &count);
	if (rc != 0) {
		throw_fat(env, "usb/fs/FatFsException", ("list failed: " + std::to_string(rc)).c_str());
		return nullptr;
	}
	if (count > 256) count = 256;
	jclass entryCls = env->FindClass("usb/fs/FatDirEntry");
	jmethodID ctor = env->GetMethodID(entryCls, "<init>", "(Ljava/lang/String;ZJB)V");
	jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), entryCls, nullptr);
	for (size_t i = 0; i < count; i++) {
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
				    jbyteArray data, jlong plannedTotalSize, jboolean create)
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
	int rc = fat_engine_write_file(static_cast<uint64_t>(volume), p.c_str(),
				       static_cast<uint64_t>(offset), buf.data(), buf.size(), &wrote,
				       static_cast<uint64_t>(plannedTotalSize), create ? 1 : 0);
	if (rc == FR_INVALID_PARAMETER && plannedTotalSize > 0) {
		/* Distinguish size rejection: check against known max via kind probe is awkward;
		 * FatFsNative Kotlin maps this specific path via FileTooLargeException by checking
		 * planned size before call; still throw typed error here. */
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
				   jlong sectorCount, jint type)
{
	int rc = fat_engine_mkfs(static_cast<uint64_t>(blockHandle),
				 static_cast<uint32_t>(sectorSize),
				 static_cast<uint64_t>(sectorCount),
				 static_cast<FatMkfsType>(type));
	if (rc != 0) throw_fat(env, "usb/fs/FatFsException", ("mkfs failed: " + std::to_string(rc)).c_str());
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
