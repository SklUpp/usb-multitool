/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ntfs_engine.h"
#include "usb_block.h"

namespace {

struct JniBlockDevice {
    JavaVM *jvm = nullptr;
    jobject device = nullptr;
    jmethodID readMid = nullptr;
    jmethodID writeMid = nullptr;
    jmethodID syncMid = nullptr;
    uint32_t sector_size = 0;
    uint64_t sector_count = 0;
    UsbBlockOps ops{};
};

struct MountedVolumeWrapper {
    uint64_t vol_handle = 0;
    JniBlockDevice *block_dev = nullptr;
};

thread_local JNIEnv *tls_env = nullptr;

JNIEnv *get_env(JavaVM *jvm)
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
    auto *dev = static_cast<JniBlockDevice *>(ctx);
    JNIEnv *env = get_env(dev->jvm);
    if (!env) return -1;

    jlong bytes = static_cast<jlong>(sectors) * dev->sector_size;
    jobject directBuf = env->NewDirectByteBuffer(buf, bytes);
    if (!directBuf) return -1;

    env->CallVoidMethod(dev->device, dev->readMid, static_cast<jlong>(lba), directBuf);
    env->DeleteLocalRef(directBuf);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return 0;
}

int jni_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors)
{
    auto *dev = static_cast<JniBlockDevice *>(ctx);
    JNIEnv *env = get_env(dev->jvm);
    if (!env) return -1;

    jlong bytes = static_cast<jlong>(sectors) * dev->sector_size;
    jobject directBuf = env->NewDirectByteBuffer(const_cast<void *>(buf), bytes);
    if (!directBuf) return -1;

    env->CallVoidMethod(dev->device, dev->writeMid, static_cast<jlong>(lba), directBuf);
    env->DeleteLocalRef(directBuf);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return 0;
}

int jni_sync(void *ctx)
{
    auto *dev = static_cast<JniBlockDevice *>(ctx);
    JNIEnv *env = get_env(dev->jvm);
    if (!env) return -1;

    env->CallVoidMethod(dev->device, dev->syncMid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return 0;
}

JniBlockDevice *create_block_device(JNIEnv *env, jobject device, jint sectorSize, jlong sectorCount)
{
    if (!device || sectorSize < 512 || sectorSize > 4096 || sectorCount <= 0) return nullptr;

    JavaVM *jvm = nullptr;
    if (env->GetJavaVM(&jvm) != 0) return nullptr;

    jclass cls = env->GetObjectClass(device);
    jmethodID readMid = env->GetMethodID(cls, "read", "(JLjava/nio/ByteBuffer;)V");
    jmethodID writeMid = env->GetMethodID(cls, "write", "(JLjava/nio/ByteBuffer;)V");
    jmethodID syncMid = env->GetMethodID(cls, "sync", "()V");

    if (!readMid || !writeMid || !syncMid) {
        return nullptr;
    }

    auto *bdev = new JniBlockDevice();
    bdev->jvm = jvm;
    bdev->device = env->NewGlobalRef(device);
    bdev->readMid = readMid;
    bdev->writeMid = writeMid;
    bdev->syncMid = syncMid;
    bdev->sector_size = static_cast<uint32_t>(sectorSize);
    bdev->sector_count = static_cast<uint64_t>(sectorCount);

    bdev->ops.ctx = bdev;
    bdev->ops.read = jni_read;
    bdev->ops.write = jni_write;
    bdev->ops.sync = jni_sync;
    bdev->ops.sector_size = bdev->sector_size;
    bdev->ops.sector_count = bdev->sector_count;

    return bdev;
}

void destroy_block_device(JNIEnv *env, JniBlockDevice *bdev)
{
    if (!bdev) return;
    if (bdev->device && env) {
        env->DeleteGlobalRef(bdev->device);
    }
    delete bdev;
}

std::string jstring_to_string(JNIEnv *env, jstring js)
{
    if (!js) return "";
    const char *p = env->GetStringUTFChars(js, nullptr);
    std::string s = p ? p : "";
    if (p) env->ReleaseStringUTFChars(js, p);
    return s;
}

/* ntfs_ucstombs emits standard UTF-8, while NewStringUTF expects modified UTF-8,
 * which cannot carry the 4-byte sequences used by astral names. Decode to UTF-16
 * instead: the C layer already rejected unconvertible names, so this input is
 * always valid UTF-8 and the resulting jstring matches the NTFS name exactly. */
std::u16string utf8_to_utf16(const char *s)
{
    std::u16string out;
    const unsigned char *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        unsigned cp;
        size_t extra;
        if (p[0] < 0x80) { cp = p[0]; extra = 0; }
        else if ((p[0] & 0xE0) == 0xC0) { cp = p[0] & 0x1Fu; extra = 1; }
        else if ((p[0] & 0xF0) == 0xE0) { cp = p[0] & 0x0Fu; extra = 2; }
        else if ((p[0] & 0xF8) == 0xF0) { cp = p[0] & 0x07u; extra = 3; }
        else break;
        p++;
        bool valid = true;
        for (size_t i = 0; i < extra; i++, p++) {
            if ((*p & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (*p & 0x3Fu);
        }
        if (!valid) break;
        if (cp >= 0x10000u) {
            cp -= 0x10000u;
            out.push_back(static_cast<char16_t>(0xD800u + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00u + (cp & 0x3FFu)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_usb_fs_NtfsFs_nativeMount(JNIEnv *env, jclass, jobject device,
                               jint sectorSize, jlong sectorCount, jboolean readOnly)
{
    JniBlockDevice *bdev = create_block_device(env, device, sectorSize, sectorCount);
    if (!bdev) return 0;

    uint64_t bhandle = usb_block_register(&bdev->ops);
    uint64_t vol_handle = ntfs_engine_mount(bhandle, readOnly != JNI_FALSE);
    if (vol_handle == 0) {
        destroy_block_device(env, bdev);
        return 0;
    }

    auto *wrapper = new MountedVolumeWrapper();
    wrapper->vol_handle = vol_handle;
    wrapper->block_dev = bdev;
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jint JNICALL
Java_usb_fs_NtfsFs_nativeClose(JNIEnv *env, jclass, jlong handle)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper) return -1;

    int rc = ntfs_engine_close(wrapper->vol_handle);
    destroy_block_device(env, wrapper->block_dev);
    delete wrapper;
    return rc;
}

JNIEXPORT jint JNICALL
Java_usb_fs_NtfsFs_nativeSync(JNIEnv *, jclass, jlong handle)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper) return -1;
    return ntfs_engine_sync(wrapper->vol_handle);
}

JNIEXPORT jobjectArray JNICALL
Java_usb_fs_NtfsFs_nativeList(JNIEnv *env, jclass, jlong handle, jstring path)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper) return nullptr;

    std::string p = jstring_to_string(env, path);
    NtfsDirEntry *entries = nullptr;
    size_t count = 0;

    int rc = ntfs_engine_list(wrapper->vol_handle, p.c_str(), &entries, &count);
    if (rc != 0) {
        jclass exCls = env->FindClass("usb/fs/NtfsException");
        if (exCls) {
            const char *msg = "directory listing failed";
            if (rc == -EOVERFLOW) msg = "directory listing exceeds the supported entry count";
            else if (rc == -EIO) msg = "directory listing failed: unreadable directory";
            env->ThrowNew(exCls, msg);
        }
        return nullptr;
    }

    jclass entryCls = env->FindClass("usb/fs/NtfsDirEntry");
    if (!entryCls) return nullptr;

    jmethodID ctor = env->GetMethodID(entryCls, "<init>", "(Ljava/lang/String;ZJ)V");
    if (!ctor) return nullptr;

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), entryCls, nullptr);
    for (size_t i = 0; i < count; i++) {
        std::u16string u16 = utf8_to_utf16(entries[i].name);
        jstring name = env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                                      static_cast<jsize>(u16.size()));
        jobject obj = env->NewObject(entryCls, ctor, name,
                                     static_cast<jboolean>(entries[i].is_directory),
                                     static_cast<jlong>(entries[i].size));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(obj);
    }
    free(entries);
    return arr;
}

JNIEXPORT jobject JNICALL
Java_usb_fs_NtfsFs_nativeStat(JNIEnv *env, jclass, jlong handle, jstring path)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper) return nullptr;

    std::string p = jstring_to_string(env, path);
    NtfsStat st{};
    int rc = ntfs_engine_stat(wrapper->vol_handle, p.c_str(), &st);
    if (rc != 0) return nullptr;

    jclass statCls = env->FindClass("usb/fs/NtfsStat");
    if (!statCls) return nullptr;

    jmethodID ctor = env->GetMethodID(statCls, "<init>", "(JZ)V");
    if (!ctor) return nullptr;

    return env->NewObject(statCls, ctor, static_cast<jlong>(st.size),
                          static_cast<jboolean>(st.is_directory));
}

JNIEXPORT jbyteArray JNICALL
Java_usb_fs_NtfsFs_nativeRead(JNIEnv *env, jclass, jlong handle, jstring path, jlong offset, jint length)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper || length < 0) return nullptr;

    std::string p = jstring_to_string(env, path);
    std::vector<uint8_t> buf(static_cast<size_t>(length));
    size_t out_read = 0;

    int rc = ntfs_engine_read(wrapper->vol_handle, p.c_str(), static_cast<uint64_t>(offset),
                              buf.data(), static_cast<size_t>(length), &out_read);
    if (rc != 0) return nullptr;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out_read));
    if (out_read > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out_read),
                                reinterpret_cast<const jbyte *>(buf.data()));
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_usb_fs_NtfsFs_nativeWrite(JNIEnv *env, jclass, jlong handle, jstring path,
                               jlong offset, jbyteArray data, jboolean create)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper || !data) return -1;

    std::string p = jstring_to_string(env, path);
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buf.data()));

    size_t out_written = 0;
    int rc = ntfs_engine_write(wrapper->vol_handle, p.c_str(), static_cast<uint64_t>(offset),
                               buf.data(), static_cast<size_t>(len),
                               create != JNI_FALSE, &out_written);
    if (rc != 0) {
        /* Never report a failed write as success: surface errno as an exception. */
        jclass exCls = env->FindClass("usb/fs/NtfsException");
        if (exCls) {
            env->ThrowNew(exCls, rc == -EROFS
                ? "write rejected: NTFS volume is mounted read-only"
                : "write failed");
        }
        return -1;
    }
    return static_cast<jint>(out_written);
}

JNIEXPORT jboolean JNICALL
Java_usb_fs_NtfsFs_nativeIsVolumeDirty(JNIEnv *, jclass, jlong handle)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper) return JNI_FALSE;
    int rc = ntfs_engine_is_dirty(wrapper->vol_handle);
    return (rc == 1) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_usb_fs_NtfsFs_nativeCheckDirty(JNIEnv *env, jclass, jobject device,
                                    jint sectorSize, jlong sectorCount)
{
    JniBlockDevice *bdev = create_block_device(env, device, sectorSize, sectorCount);
    if (!bdev) return JNI_FALSE;

    uint64_t bhandle = usb_block_register(&bdev->ops);
    int rc = ntfs_engine_check_dirty_dev(bhandle);
    destroy_block_device(env, bdev);
    return (rc == 1) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_usb_fs_NtfsFs_nativeClearDirty(JNIEnv *env, jclass, jobject device,
                                    jint sectorSize, jlong sectorCount,
                                    jstring confirmationToken)
{
    JniBlockDevice *bdev = create_block_device(env, device, sectorSize, sectorCount);
    if (!bdev) return JNI_FALSE;

    std::string token = jstring_to_string(env, confirmationToken);
    uint64_t bhandle = usb_block_register(&bdev->ops);
    int rc = ntfs_engine_clear_dirty(bhandle, token.c_str());
    destroy_block_device(env, bdev);
    return (rc == 0) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
