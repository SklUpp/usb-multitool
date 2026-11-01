/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * JNI facade for vendored exfatprogs fsck over the shared relative-LBA
 * usb_block_* contract. Partition offset is applied exactly once by the
 * BlockDevice behind the handle; this layer never adds a second offset.
 */
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <pthread.h>

#include "usb_block.h"

extern "C" {
#include "libexfat.h"
#include "exfat_fsck_lib.h"
}

namespace {

struct HandleRec {
    uint64_t sector_size;
    uint64_t sector_count;
    bool closed_volumes;
    jobject device; /* GlobalRef to usb.device.BlockDevice */
};

constexpr int kMaxHandles = 8;
HandleRec g_handles[kMaxHandles];
bool g_handle_used[kMaxHandles];

JavaVM *g_vm = nullptr;
jmethodID g_read_mid = nullptr;
jmethodID g_write_mid = nullptr;
jmethodID g_sync_mid = nullptr;
jclass g_bytebuffer_cls = nullptr;
jmethodID g_bb_allocate_direct = nullptr;
jmethodID g_bb_position = nullptr;
jmethodID g_bb_clear = nullptr;

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

JNIEnv *env_for_current_thread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) ==
        JNI_OK)
        return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != 0)
        return nullptr;
    return env;
}

int ensure_method_cache(JNIEnv *env) {
    if (g_read_mid)
        return 0;
    jclass dev_cls = env->FindClass("usb/device/BlockDevice");
    if (!dev_cls)
        return -ENOENT;
    /* Kotlin ULong mangling for interface methods. */
    g_read_mid = env->GetMethodID(dev_cls, "read-WZ4Q5Ns",
                                  "(JLjava/nio/ByteBuffer;)V");
    g_write_mid = env->GetMethodID(dev_cls, "write-WZ4Q5Ns",
                                   "(JLjava/nio/ByteBuffer;)V");
    g_sync_mid = env->GetMethodID(dev_cls, "sync", "()V");
    env->DeleteLocalRef(dev_cls);
    if (!g_read_mid || !g_write_mid || !g_sync_mid)
        return -ENOENT;

    jclass bb = env->FindClass("java/nio/ByteBuffer");
    if (!bb)
        return -ENOENT;
    g_bytebuffer_cls = reinterpret_cast<jclass>(env->NewGlobalRef(bb));
    env->DeleteLocalRef(bb);
    g_bb_allocate_direct = env->GetStaticMethodID(
        g_bytebuffer_cls, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    g_bb_position =
        env->GetMethodID(g_bytebuffer_cls, "position", "(I)Ljava/nio/Buffer;");
    g_bb_clear = env->GetMethodID(g_bytebuffer_cls, "clear", "()Ljava/nio/Buffer;");
    if (!g_bb_allocate_direct || !g_bb_position || !g_bb_clear)
        return -ENOENT;
    return 0;
}

int java_transfer(HandleRec *h, int writing, uint64_t lba, void *buf,
                  const void *cbuf, uint32_t sectors) {
    JNIEnv *env = env_for_current_thread();
    if (!env)
        return -EIO;
    if (ensure_method_cache(env) != 0)
        return -EIO;

    const size_t bytes =
        static_cast<size_t>(sectors) * static_cast<size_t>(h->sector_size);
    if (bytes > static_cast<size_t>(INT32_MAX))
        return -EINVAL;

    jobject bb = env->CallStaticObjectMethod(
        g_bytebuffer_cls, g_bb_allocate_direct, static_cast<jint>(bytes));
    if (!bb || env->ExceptionCheck()) {
        env->ExceptionClear();
        return -ENOMEM;
    }

    void *direct = env->GetDirectBufferAddress(bb);
    if (!direct) {
        env->DeleteLocalRef(bb);
        return -ENOMEM;
    }

    if (writing)
        std::memcpy(direct, cbuf, bytes);

    env->CallObjectMethod(bb, g_bb_clear);
    if (writing) {
        env->CallVoidMethod(h->device, g_write_mid, static_cast<jlong>(lba), bb);
    } else {
        env->CallVoidMethod(h->device, g_read_mid, static_cast<jlong>(lba), bb);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(bb);
        return -EIO;
    }

    if (!writing)
        std::memcpy(buf, direct, bytes);

    env->DeleteLocalRef(bb);
    return 0;
}

int java_read(void *ctx, uint64_t lba, void *buf, uint32_t sectors) {
    return java_transfer(static_cast<HandleRec *>(ctx), 0, lba, buf, nullptr,
                         sectors);
}

int java_write(void *ctx, uint64_t lba, const void *buf, uint32_t sectors) {
    return java_transfer(static_cast<HandleRec *>(ctx), 1, lba, nullptr, buf,
                         sectors);
}

int java_sync(void *ctx) {
    HandleRec *h = static_cast<HandleRec *>(ctx);
    JNIEnv *env = env_for_current_thread();
    if (!env)
        return -EIO;
    if (ensure_method_cache(env) != 0)
        return -EIO;
    env->CallVoidMethod(h->device, g_sync_mid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -EIO;
    }
    return 0;
}

const exfat_block_ops kUsbBlockOps = {
    usb_block_read,
    usb_block_write,
    usb_block_sync,
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_fs_ExfatFsck_nativeOpen(
        JNIEnv *env,
        jclass,
        jobject device,
        jlong sectorSize,
        jlong sectorCount) {
    if (!device || sectorSize <= 0 || sectorCount <= 0)
        return 0;

    pthread_mutex_lock(&g_mu);
    int slot = -1;
    for (int i = 0; i < kMaxHandles; ++i) {
        if (!g_handle_used[i]) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        pthread_mutex_unlock(&g_mu);
        return 0;
    }

    jobject global = env->NewGlobalRef(device);
    if (!global) {
        pthread_mutex_unlock(&g_mu);
        return 0;
    }

    HandleRec &h = g_handles[slot];
    h = HandleRec{
        static_cast<uint64_t>(sectorSize),
        static_cast<uint64_t>(sectorCount),
        false,
        global,
    };
    g_handle_used[slot] = true;
    const uint64_t handle = static_cast<uint64_t>(slot + 1);

    int rc = usb_block_register(handle, h.sector_size, h.sector_count,
                                java_read, java_write, java_sync, &h);
    if (rc != 0) {
        env->DeleteGlobalRef(global);
        g_handle_used[slot] = false;
        std::memset(&h, 0, sizeof(h));
        pthread_mutex_unlock(&g_mu);
        return 0;
    }
    pthread_mutex_unlock(&g_mu);
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_ExfatFsck_nativeClose(JNIEnv *env, jclass, jlong handle) {
    if (handle <= 0 || handle > kMaxHandles)
        return;
    pthread_mutex_lock(&g_mu);
    if (!g_handle_used[handle - 1]) {
        pthread_mutex_unlock(&g_mu);
        return;
    }
    usb_block_unregister(static_cast<uint64_t>(handle));
    if (g_handles[handle - 1].device) {
        env->DeleteGlobalRef(g_handles[handle - 1].device);
    }
    g_handle_used[handle - 1] = false;
    std::memset(&g_handles[handle - 1], 0, sizeof(HandleRec));
    pthread_mutex_unlock(&g_mu);
}

extern "C" JNIEXPORT void JNICALL
Java_usb_fs_ExfatFsck_nativeMarkVolumesClosed(JNIEnv *, jclass, jlong handle) {
    if (handle <= 0 || handle > kMaxHandles)
        return;
    pthread_mutex_lock(&g_mu);
    if (g_handle_used[handle - 1])
        g_handles[handle - 1].closed_volumes = true;
    pthread_mutex_unlock(&g_mu);
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_fs_ExfatFsck_nativeCheck(JNIEnv *, jclass, jlong handle) {
    if (handle <= 0 || handle > kMaxHandles)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;

    pthread_mutex_lock(&g_mu);
    if (!g_handle_used[handle - 1]) {
        pthread_mutex_unlock(&g_mu);
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;
    }
    HandleRec h = g_handles[handle - 1];
    pthread_mutex_unlock(&g_mu);

    struct exfat_blk_dev bd;
    int rc = exfat_setup_block_dev(
        &bd,
        static_cast<uint64_t>(handle),
        &kUsbBlockOps,
        static_cast<unsigned int>(h.sector_size),
        h.sector_count);
    if (rc < 0)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;
    int exit_code = exfat_fsck_on_blk_dev(&bd, /*repair_yes=*/0);
    exfat_deinit_blk_dev_info(&bd);
    return exit_code;
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_fs_ExfatFsck_nativeRepair(
        JNIEnv *env,
        jclass,
        jlong handle,
        jstring confirmationToken) {
    if (handle <= 0 || handle > kMaxHandles)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;
    if (confirmationToken == nullptr)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;

    const char *token = env->GetStringUTFChars(confirmationToken, nullptr);
    if (token == nullptr)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;
    const bool token_ok = std::strcmp(token, "CONFIRM_EXFAT_REPAIR") == 0;
    env->ReleaseStringUTFChars(confirmationToken, token);
    if (!token_ok)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;

    pthread_mutex_lock(&g_mu);
    if (!g_handle_used[handle - 1] || !g_handles[handle - 1].closed_volumes) {
        pthread_mutex_unlock(&g_mu);
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;
    }
    HandleRec h = g_handles[handle - 1];
    pthread_mutex_unlock(&g_mu);

    struct exfat_blk_dev bd;
    int rc = exfat_setup_block_dev(
        &bd,
        static_cast<uint64_t>(handle),
        &kUsbBlockOps,
        static_cast<unsigned int>(h.sector_size),
        h.sector_count);
    if (rc < 0)
        return EXFAT_FSCK_EXIT_OPERATION_ERROR;

    int exit_code = exfat_fsck_on_blk_dev(&bd, /*repair_yes=*/1);
    exfat_deinit_blk_dev_info(&bd);
    return exit_code;
}
