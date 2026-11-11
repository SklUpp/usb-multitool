/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ext_engine.h"
#include "ext_name_utf8.h"
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
    uint64_t block_handle = 0;
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
    if (!readMid || !writeMid || !syncMid) return nullptr;

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
    if (bdev->device && env) env->DeleteGlobalRef(bdev->device);
    delete bdev;
}

void throw_ext(JNIEnv *env, const char *msg);

/* GetStringUTFChars is modified UTF-8/CESU-8; lwext4 and on-disk names are standard
 * UTF-8. Convert Java UTF-16 via ext_utf16_to_utf8 so non-BMP path segments match
 * list output. Refuse unpaired surrogates and embedded NULs. */
bool jstring_to_utf8(JNIEnv *env, jstring js, std::string &out)
{
    out.clear();
    if (!js) return true;
    const jsize len = env->GetStringLength(js);
    if (len == 0) return true;
    const jchar *chars = env->GetStringChars(js, nullptr);
    if (!chars) return false;
    std::string buffer(static_cast<size_t>(len) * 3 + 1, '\0');
    int n = ext_utf16_to_utf8(reinterpret_cast<const uint16_t *>(chars),
                              static_cast<size_t>(len), &buffer[0], buffer.size());
    env->ReleaseStringChars(js, chars);
    if (n < 0) return false;
    for (int i = 0; i < n; i++) {
        if (buffer[static_cast<size_t>(i)] == '\0') return false;
    }
    out.assign(buffer.data(), static_cast<size_t>(n));
    return true;
}

bool path_from_jstring(JNIEnv *env, jstring js, std::string &out)
{
    if (jstring_to_utf8(env, js, out)) return true;
    throw_ext(env, "invalid path encoding");
    return false;
}

/* On-disk names are standard UTF-8; NewStringUTF cannot carry 4-byte sequences.
 * Malformed bytes fail the whole listing (see ext_utf8_to_utf16_strict) instead of
 * being dropped or replaced, which would alias two entries under one display name. */
bool utf8_name_to_utf16(const char *name, std::u16string &out)
{
    out.clear();
    if (!name) return true;
    const size_t cap = std::strlen(name) + 1;
    std::vector<uint16_t> units(cap);
    size_t len = 0;
    if (!ext_utf8_to_utf16_strict(name, units.data(), units.size(), &len)) return false;
    out.reserve(len);
    for (size_t i = 0; i < len; i++) out.push_back(static_cast<char16_t>(units[i]));
    return true;
}

void throw_ext(JNIEnv *env, const char *msg)
{
    jclass exCls = env->FindClass("usb/fs/ExtException");
    if (exCls) env->ThrowNew(exCls, msg);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_usb_fs_ExtFs_nativeMount(JNIEnv *env, jclass, jobject device, jint sectorSize,
                              jlong sectorCount, jboolean readOnly, jintArray outErrno)
{
    JniBlockDevice *bdev = create_block_device(env, device, sectorSize, sectorCount);
    if (!bdev) {
        if (outErrno) {
            jint e = EINVAL;
            env->SetIntArrayRegion(outErrno, 0, 1, &e);
        }
        return 0;
    }
    uint64_t bhandle = usb_block_register(&bdev->ops);
    int err = 0;
    uint64_t vol = ext_engine_mount(bhandle, readOnly != JNI_FALSE, &err);
    if (vol == 0) {
        usb_block_unregister(bhandle);
        destroy_block_device(env, bdev);
        if (outErrno) {
            jint e = err;
            env->SetIntArrayRegion(outErrno, 0, 1, &e);
        }
        return 0;
    }
    auto *wrapper = new MountedVolumeWrapper();
    wrapper->vol_handle = vol;
    wrapper->block_handle = bhandle;
    wrapper->block_dev = bdev;
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jint JNICALL
Java_usb_fs_ExtFs_nativeClose(JNIEnv *env, jclass, jlong handle)
{
    auto *wrapper = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!wrapper) return -1;
    int rc = ext_engine_close(wrapper->vol_handle);
    if (wrapper->block_handle != 0) {
        usb_block_unregister(wrapper->block_handle);
        wrapper->block_handle = 0;
    }
    destroy_block_device(env, wrapper->block_dev);
    delete wrapper;
    return rc;
}

JNIEXPORT jint JNICALL
Java_usb_fs_ExtFs_nativeSync(JNIEnv *, jclass, jlong handle)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) return -1;
    return ext_engine_sync(w->vol_handle);
}

JNIEXPORT jobjectArray JNICALL
Java_usb_fs_ExtFs_nativeList(JNIEnv *env, jclass, jlong handle, jstring path)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) return nullptr;
    std::string p;
    if (!path_from_jstring(env, path, p)) return nullptr;
    ExtDirEntry *entries = nullptr;
    size_t count = 0;
    int rc = ext_engine_list(w->vol_handle, p.c_str(), &entries, &count);
    if (rc != 0) {
        // Match ntfs_jni / delete path: only ENOENT is path-absent (UI may fall back to
        // root). EIO and other faults keep a generic listing-failed message so they
        // surface as filesError, never as a silent empty folder.
        const char *msg = "directory listing failed";
        if (rc == -EOVERFLOW) {
            msg = "directory listing exceeds the supported entry count";
        } else if (rc == -ENOENT) {
            msg = "Path not found";
        } else if (rc == -ENOTDIR) {
            msg = "Not a directory";
        } else if (rc == -EIO) {
            msg = "directory listing failed: unreadable directory";
        }
        throw_ext(env, msg);
        return nullptr;
    }
    jclass entryCls = env->FindClass("usb/fs/ExtDirEntry");
    if (!entryCls) {
        free(entries);
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(entryCls, "<init>", "(Ljava/lang/String;ZJ)V");
    if (!ctor) {
        free(entries);
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), entryCls, nullptr);
    if (!arr) {
        free(entries);
        return nullptr;
    }
    for (size_t i = 0; i < count; i++) {
        std::u16string u16;
        if (!utf8_name_to_utf16(entries[i].name, u16)) {
            free(entries);
            throw_ext(env, "directory listing failed: invalid filename encoding");
            return nullptr;
        }
        jstring name = env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                                      static_cast<jsize>(u16.size()));
        if (!name || env->ExceptionCheck()) {
            free(entries);
            return nullptr;
        }
        jobject obj = env->NewObject(entryCls, ctor, name,
                                     static_cast<jboolean>(entries[i].is_directory),
                                     static_cast<jlong>(entries[i].size));
        env->DeleteLocalRef(name);
        if (!obj || env->ExceptionCheck()) {
            free(entries);
            return nullptr;
        }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);
        env->DeleteLocalRef(obj);
    }
    free(entries);
    return arr;
}

JNIEXPORT jobject JNICALL
Java_usb_fs_ExtFs_nativeStat(JNIEnv *env, jclass, jlong handle, jstring path,
                             jintArray outErrno)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) {
        if (outErrno) {
            jint e = EINVAL;
            env->SetIntArrayRegion(outErrno, 0, 1, &e);
        }
        return nullptr;
    }
    std::string p;
    if (!path_from_jstring(env, path, p)) {
        if (outErrno) {
            jint e = EINVAL;
            env->SetIntArrayRegion(outErrno, 0, 1, &e);
        }
        return nullptr;
    }
    ExtStat st{};
    int rc = ext_engine_stat(w->vol_handle, p.c_str(), &st);
    if (rc != 0) {
        if (outErrno) {
            jint e = rc; /* negative errno from engine */
            env->SetIntArrayRegion(outErrno, 0, 1, &e);
        }
        return nullptr;
    }
    if (outErrno) {
        jint e = 0;
        env->SetIntArrayRegion(outErrno, 0, 1, &e);
    }
    jclass cls = env->FindClass("usb/fs/ExtStat");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JZ)V");
    if (!ctor) return nullptr;
    return env->NewObject(cls, ctor, static_cast<jlong>(st.size),
                          static_cast<jboolean>(st.is_directory));
}

JNIEXPORT jbyteArray JNICALL
Java_usb_fs_ExtFs_nativeRead(JNIEnv *env, jclass, jlong handle, jstring path,
                             jlong offset, jint length)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w || length < 0) return nullptr;
    std::string p;
    if (!path_from_jstring(env, path, p)) return nullptr;
    std::vector<uint8_t> buf(static_cast<size_t>(length));
    size_t got = 0;
    if (ext_engine_read(w->vol_handle, p.c_str(),
                        static_cast<uint64_t>(offset), buf.data(),
                        static_cast<size_t>(length), &got) != 0) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(got));
    if (got > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(got),
                                reinterpret_cast<const jbyte *>(buf.data()));
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_usb_fs_ExtFs_nativeWrite(JNIEnv *env, jclass, jlong handle, jstring path,
                              jlong offset, jbyteArray data, jboolean create)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w || !data) return -1;
    std::string p;
    if (!path_from_jstring(env, path, p)) return -1;
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buf.data()));
    size_t wrote = 0;
    int rc = ext_engine_write(w->vol_handle, p.c_str(),
                              static_cast<uint64_t>(offset), buf.data(),
                              static_cast<size_t>(len), create != JNI_FALSE, &wrote);
    if (rc != 0) {
        throw_ext(env, rc == -EROFS ? "write rejected: EXT volume is mounted read-only"
                                    : "write failed");
        return -1;
    }
    return static_cast<jint>(wrote);
}

JNIEXPORT void JNICALL
Java_usb_fs_ExtFs_nativeMkdir(JNIEnv *env, jclass, jlong handle, jstring path)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) return;
    std::string p;
    if (!path_from_jstring(env, path, p)) return;
    int rc = ext_engine_mkdir(w->vol_handle, p.c_str());
    if (rc != 0) throw_ext(env, "mkdir failed");
}

JNIEXPORT void JNICALL
Java_usb_fs_ExtFs_nativeDelete(JNIEnv *env, jclass, jlong handle, jstring path)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) return;
    std::string p;
    if (!path_from_jstring(env, path, p)) return;
    int rc = ext_engine_delete(w->vol_handle, p.c_str());
    if (rc != 0) {
        const char *msg = "delete failed";
        if (rc == -ENOENT) msg = "Path not found";
        else if (rc == -ENOTEMPTY) msg = "Directory not empty";
        throw_ext(env, msg);
    }
}

JNIEXPORT void JNICALL
Java_usb_fs_ExtFs_nativeRename(JNIEnv *env, jclass, jlong handle, jstring from, jstring to)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) return;
    std::string from_path;
    std::string to_path;
    if (!path_from_jstring(env, from, from_path) || !path_from_jstring(env, to, to_path)) return;
    int rc = ext_engine_rename(w->vol_handle, from_path.c_str(), to_path.c_str());
    if (rc != 0) throw_ext(env, "rename failed");
}

JNIEXPORT jint JNICALL
Java_usb_fs_ExtFs_nativeMkfs(JNIEnv *env, jclass, jobject device, jint sectorSize,
                             jlong sectorCount, jstring label)
{
    JniBlockDevice *bdev = create_block_device(env, device, sectorSize, sectorCount);
    if (!bdev) return -EINVAL;
    uint64_t bhandle = usb_block_register(&bdev->ops);
    /*
     * lwext4 copies the label bytes into ext4_superblock.volume_name verbatim, and the
     * Kotlin validation counts standard UTF-8. GetStringUTFChars would hand over Java's
     * modified UTF-8 (CESU-8), where a supplementary code point becomes two 3-byte
     * surrogate sequences, so the two sides would disagree about the 15-byte ceiling and
     * the stored label would not be valid UTF-8.
     */
    char label_utf8[64];
    const jsize label_len = label ? env->GetStringLength(label) : 0;
    const jchar *label_chars =
        label_len > 0 ? env->GetStringChars(label, nullptr) : nullptr;
    int converted = 0;
    if (label_chars) {
        converted = ext_utf16_to_utf8(reinterpret_cast<const uint16_t *>(label_chars),
                                      static_cast<size_t>(label_len), label_utf8,
                                      sizeof(label_utf8));
        env->ReleaseStringChars(label, label_chars);
    }
    int rc = converted < 0 ? -EINVAL
                           : ext_engine_mkfs(bhandle, converted > 0 ? label_utf8 : nullptr);
    usb_block_unregister(bhandle);
    destroy_block_device(env, bdev);
    return rc;
}

JNIEXPORT jboolean JNICALL
Java_usb_fs_ExtFs_nativeUsage(JNIEnv *env, jclass, jlong handle, jlongArray out)
{
    auto *w = reinterpret_cast<MountedVolumeWrapper *>(handle);
    if (!w) {
        throw_ext(env, "space query failed: bad handle");
        return JNI_FALSE;
    }
    if (!out || env->GetArrayLength(out) < 2) {
        throw_ext(env, "space query: output array too small");
        return JNI_FALSE;
    }
    uint64_t total = 0;
    uint64_t free_bytes = 0;
    int rc = ext_engine_usage(w->vol_handle, &total, &free_bytes);
    if (rc != 0) {
        throw_ext(env, "space query failed");
        return JNI_FALSE;
    }
    jlong values[2] = { static_cast<jlong>(total), static_cast<jlong>(free_bytes) };
    env->SetLongArrayRegion(out, 0, 2, values);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_usb_fs_ExtFs_nativeProbe(JNIEnv *env, jclass, jobject device, jint sectorSize,
                              jlong sectorCount)
{
    JniBlockDevice *bdev = create_block_device(env, device, sectorSize, sectorCount);
    if (!bdev) return JNI_FALSE;
    uint64_t bhandle = usb_block_register(&bdev->ops);
    int rc = ext_engine_probe(bhandle);
    usb_block_unregister(bhandle);
    destroy_block_device(env, bdev);
    return rc == 1 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
