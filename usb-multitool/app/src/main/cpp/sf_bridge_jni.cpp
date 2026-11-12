/*
 * JNI facade for the SectorForge sf-core rescue engine (MIT; pin and patch
 * provenance in third_party/README.md).
 *
 * The USB device is only reachable through Kotlin (UsbDeviceConnection), so the
 * Rust bridge owns the imaging algorithm while every read/write crosses back into
 * Kotlin through a direct ByteBuffer that wraps the Rust buffer. No sector data is
 * copied on the Java side of the bridge.
 *
 * The Rust side never lets a panic escape: cancellation and transport failures are
 * unwound inside Rust and reported as status codes.
 */
#include <jni.h>

#include <cstdint>
#include <limits>
#include <mutex>

#if USB_WITH_SECTORFORGE

extern "C" {
/* Mirrors app/src/main/rust/sf-bridge/src/lib.rs (repr(C)). */
struct SfTriple {
    uint64_t a;
    uint64_t b;
    uint64_t c;
};

struct SfCallbacks {
    void *ctx;
    int32_t (*read_at)(void *ctx, uint64_t offset, uint8_t *out, size_t len);
    int32_t (*write_at)(void *ctx, uint64_t offset, const uint8_t *data, size_t len);
    int32_t (*progress)(void *ctx, uint64_t finished, uint64_t bad, uint64_t pending,
                        uint64_t position, const SfTriple *blocks, size_t count);
};

uint64_t sf_run_start(const SfCallbacks *callbacks, uint64_t size, uint64_t sector,
                      const SfTriple *seed, size_t seed_len, uint64_t initial_chunk,
                      uint64_t skip, uint32_t max_retries, uint32_t reverse,
                      uint64_t checkpoint_bytes);
int32_t sf_run_execute(uint64_t handle);
uint64_t sf_run_blocks(uint64_t handle, SfTriple *out, size_t capacity);
void sf_run_free(uint64_t handle);
}

namespace {

constexpr size_t kMaxRuns = 4;
constexpr jint kReadMedium = 1;
constexpr jint kReadFatal = 2;

struct BridgeCtx {
    JavaVM *vm;
    jobject reader;
    jobject writer;
    jobject progress;
    jmethodID read_method;
    jmethodID write_method;
    jmethodID progress_method;
    jthrowable failure;
};

std::mutex g_runs_mutex;
BridgeCtx *g_runs[kMaxRuns] = {};

JNIEnv *env_for(BridgeCtx *ctx) {
    JNIEnv *env = nullptr;
    if (ctx->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (ctx->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
}

void capture_exception(JNIEnv *env, BridgeCtx *ctx) {
    if (!env->ExceptionCheck()) return;
    jthrowable cause = env->ExceptionOccurred();
    env->ExceptionClear();
    if (ctx->failure == nullptr && cause != nullptr) {
        ctx->failure = static_cast<jthrowable>(env->NewGlobalRef(cause));
    }
    if (cause != nullptr) env->DeleteLocalRef(cause);
}

int32_t read_at(void *raw, uint64_t offset, uint8_t *out, size_t len) {
    auto *ctx = static_cast<BridgeCtx *>(raw);
    JNIEnv *env = env_for(ctx);
    if (env == nullptr) return kReadFatal;
    jobject buffer = env->NewDirectByteBuffer(out, static_cast<jlong>(len));
    if (buffer == nullptr) {
        capture_exception(env, ctx);
        return kReadFatal;
    }
    jint status = env->CallIntMethod(ctx->reader, ctx->read_method,
                                     static_cast<jlong>(offset), buffer);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        capture_exception(env, ctx);
        return kReadFatal;
    }
    return status;
}

int32_t write_at(void *raw, uint64_t offset, const uint8_t *data, size_t len) {
    auto *ctx = static_cast<BridgeCtx *>(raw);
    JNIEnv *env = env_for(ctx);
    if (env == nullptr) return kReadFatal;
    jobject buffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(data),
                                              static_cast<jlong>(len));
    if (buffer == nullptr) {
        capture_exception(env, ctx);
        return kReadFatal;
    }
    jint status = env->CallIntMethod(ctx->writer, ctx->write_method,
                                     static_cast<jlong>(offset), buffer);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        capture_exception(env, ctx);
        return kReadFatal;
    }
    return status;
}

int32_t progress(void *raw, uint64_t finished, uint64_t bad, uint64_t pending,
                 uint64_t position, const SfTriple *blocks, size_t count) {
    auto *ctx = static_cast<BridgeCtx *>(raw);
    JNIEnv *env = env_for(ctx);
    if (env == nullptr) return 1;
    jlongArray array = nullptr;
    if (blocks != nullptr && count > 0) {
        const jsize length = static_cast<jsize>(count * 3);
        array = env->NewLongArray(length);
        if (array == nullptr) {
            capture_exception(env, ctx);
            return 1;
        }
        /* SfTriple is three uint64_t, layout-compatible with jlong[]. */
        env->SetLongArrayRegion(array, 0, length, reinterpret_cast<const jlong *>(blocks));
        if (env->ExceptionCheck()) {
            capture_exception(env, ctx);
            env->DeleteLocalRef(array);
            return 1;
        }
    }
    jboolean keep_going = env->CallBooleanMethod(
        ctx->progress, ctx->progress_method,
        static_cast<jlong>(finished), static_cast<jlong>(bad),
        static_cast<jlong>(pending), static_cast<jlong>(position), array);
    if (array != nullptr) env->DeleteLocalRef(array);
    if (env->ExceptionCheck()) {
        capture_exception(env, ctx);
        return 1;
    }
    return keep_going == JNI_TRUE ? 0 : 1;
}

void release(BridgeCtx *ctx) {
    if (ctx == nullptr) return;
    JNIEnv *env = env_for(ctx);
    if (env != nullptr) {
        if (ctx->reader != nullptr) env->DeleteGlobalRef(ctx->reader);
        if (ctx->writer != nullptr) env->DeleteGlobalRef(ctx->writer);
        if (ctx->failure != nullptr) env->DeleteGlobalRef(ctx->failure);
        if (ctx->progress != nullptr) env->DeleteGlobalRef(ctx->progress);
    }
    delete ctx;
}

BridgeCtx *take_ctx(uint64_t handle) {
    if (handle == 0 || handle > kMaxRuns) return nullptr;
    std::lock_guard<std::mutex> lock(g_runs_mutex);
    BridgeCtx *ctx = g_runs[handle - 1];
    g_runs[handle - 1] = nullptr;
    return ctx;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeEnginePresent(JNIEnv *, jclass) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeStart(
    JNIEnv *env, jclass, jobject reader, jobject writer, jobject progress_target,
    jlongArray seeds, jlong size_bytes, jint sector_size, jint initial_chunk,
    jint skip_bytes, jint max_retries, jboolean reverse, jlong checkpoint_bytes) {
    if (reader == nullptr || writer == nullptr || progress_target == nullptr) return 0;
    if (size_bytes <= 0 || sector_size < 512 || seeds == nullptr) return 0;
    const jsize seed_length = env->GetArrayLength(seeds);
    if (seed_length < 0 || seed_length % 3 != 0) return 0;
    auto *ctx = new BridgeCtx{};
    env->GetJavaVM(&ctx->vm);
    ctx->reader = env->NewGlobalRef(reader);
    ctx->writer = env->NewGlobalRef(writer);
    ctx->progress = env->NewGlobalRef(progress_target);

    jclass reader_class = env->GetObjectClass(reader);
    jclass writer_class = env->GetObjectClass(writer);
    jclass progress_class = env->GetObjectClass(progress_target);
    ctx->read_method = env->GetMethodID(reader_class, "readAt", "(JLjava/nio/ByteBuffer;)I");
    ctx->write_method = env->GetMethodID(writer_class, "writeAt", "(JLjava/nio/ByteBuffer;)I");
    ctx->progress_method = env->GetMethodID(progress_class, "onProgress", "(JJJJ[J)Z");
    env->DeleteLocalRef(reader_class);
    env->DeleteLocalRef(writer_class);
    env->DeleteLocalRef(progress_class);
    if (ctx->read_method == nullptr || ctx->write_method == nullptr ||
        ctx->progress_method == nullptr) {
        env->ExceptionClear();
        release(ctx);
        return 0;
    }

    jlong *seed_values = nullptr;
    if (seed_length > 0) {
        seed_values = env->GetLongArrayElements(seeds, nullptr);
        if (seed_values == nullptr) {
            env->ExceptionClear();
            release(ctx);
            return 0;
        }
    }

    SfCallbacks callbacks{};
    callbacks.ctx = ctx;
    callbacks.read_at = read_at;
    callbacks.write_at = write_at;
    callbacks.progress = progress;

    uint64_t handle = sf_run_start(
        &callbacks, static_cast<uint64_t>(size_bytes),
        static_cast<uint64_t>(sector_size),
        reinterpret_cast<const SfTriple *>(seed_values),
        static_cast<size_t>(seed_length / 3),
        static_cast<uint64_t>(initial_chunk), static_cast<uint64_t>(skip_bytes),
        static_cast<uint32_t>(max_retries), reverse == JNI_TRUE ? 1u : 0u,
        static_cast<uint64_t>(checkpoint_bytes));
    if (seed_values != nullptr) env->ReleaseLongArrayElements(seeds, seed_values, JNI_ABORT);
    if (handle == 0) {
        release(ctx);
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(g_runs_mutex);
        if (handle <= kMaxRuns) g_runs[handle - 1] = ctx;
    }
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeExecute(JNIEnv *env, jclass, jlong handle) {
    jint status = static_cast<jint>(sf_run_execute(static_cast<uint64_t>(handle)));
    std::lock_guard<std::mutex> lock(g_runs_mutex);
    if (handle > 0 && static_cast<uint64_t>(handle) <= kMaxRuns) {
        BridgeCtx *ctx = g_runs[handle - 1];
        if (ctx != nullptr && ctx->failure != nullptr) env->Throw(ctx->failure);
    }
    return status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeBlocks(JNIEnv *env, jclass, jlong handle,
                                                    jlongArray out) {
    jsize capacity = env->GetArrayLength(out);
    if (capacity == 0) {
        uint64_t needed = sf_run_blocks(static_cast<uint64_t>(handle), nullptr, 0);
        return needed == std::numeric_limits<uint64_t>::max() ? -1
                                                             : static_cast<jlong>(needed);
    }
    jlong *values = env->GetLongArrayElements(out, nullptr);
    if (values == nullptr) {
        env->ExceptionClear();
        return -1;
    }
    uint64_t needed = sf_run_blocks(static_cast<uint64_t>(handle),
                                    reinterpret_cast<SfTriple *>(values),
                                    static_cast<size_t>(capacity / 3));
    env->ReleaseLongArrayElements(out, values, 0);
    if (needed == std::numeric_limits<uint64_t>::max()) return -1;
    return static_cast<jlong>(needed);
}

extern "C" JNIEXPORT void JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    sf_run_free(static_cast<uint64_t>(handle));
    release(take_ctx(static_cast<uint64_t>(handle)));
}

#else  /* !USB_WITH_SECTORFORGE */

extern "C" JNIEXPORT jboolean JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeEnginePresent(JNIEnv *, jclass) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeStart(
    JNIEnv *, jclass, jobject, jobject, jobject, jlongArray, jlong, jint, jint, jint, jint,
    jboolean, jlong) {
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeExecute(JNIEnv *, jclass, jlong) {
    return 3; /* bad handle: no engine */
}

extern "C" JNIEXPORT jlong JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeBlocks(JNIEnv *, jclass, jlong, jlongArray) {
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_usb_diagnostics_SectorForgeNative_nativeFree(JNIEnv *, jclass, jlong) {}

#endif  /* USB_WITH_SECTORFORGE */
