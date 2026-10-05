#include "onnx_env.h"
#include <android/log.h>
#include <mutex>
#include <dlfcn.h>
#include <sys/system_properties.h>

static OrtEnv* g_shared_env = nullptr;
static std::mutex g_env_mutex;
static bool g_env_created = false;
static OrtThreadingOptions* g_threading_options = nullptr;

// 共享 intra-op 线程池：所有 ONNX 模型（联想/标点/手写/离线语音 ASR）共用一个
// env，因此共用一个线程池，避免每个 session 各自创建线程导致资源/内存浪费。
#define ONNX_INTRA_OP_THREADS 2

#define LOG_TAG "OnnxEnv"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const OrtApi* OnnxGetApi() {
    static const OrtApi* api = nullptr;
    if (!api) {
        api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!api) {
            LOGE("Failed to get ONNX Runtime API");
        }
    }
    return api;
}

OrtEnv* OnnxGetSharedEnv() {
    std::lock_guard<std::mutex> lock(g_env_mutex);
    if (!g_shared_env) {
        const OrtApi* api = OnnxGetApi();
        if (api) {
            OrtStatus* status = api->CreateThreadingOptions(&g_threading_options);
            if (!status) {
                status = api->SetGlobalIntraOpNumThreads(g_threading_options, ONNX_INTRA_OP_THREADS);
            }
            if (!status) {
                status = api->CreateEnvWithGlobalThreadPools(
                    ORT_LOGGING_LEVEL_WARNING, "xime_onnx", g_threading_options, &g_shared_env);
            }
            if (status) {
                LOGE("Failed to create shared env: %s", api->GetErrorMessage(status));
                api->ReleaseStatus(status);
                if (g_threading_options) {
                    api->ReleaseThreadingOptions(g_threading_options);
                    g_threading_options = nullptr;
                }
            } else {
                g_env_created = true;
                LOGD("Onnx shared env created (global thread pool, %d intra-op threads)",
                     ONNX_INTRA_OP_THREADS);
            }
        }
    }
    return g_shared_env;
}

void OnnxReleaseSharedEnv() {
    std::lock_guard<std::mutex> lock(g_env_mutex);
    const OrtApi* api = OnnxGetApi();
    if (g_shared_env) {
        if (api) api->ReleaseEnv(g_shared_env);
        g_shared_env = nullptr;
        g_env_created = false;
        LOGD("Onnx shared env released");
    }
    if (g_threading_options) {
        if (api) api->ReleaseThreadingOptions(g_threading_options);
        g_threading_options = nullptr;
    }
}

bool OnnxTryEnableNnapi(OrtSessionOptions* options) {
    const OrtApi* api = OnnxGetApi();
    if (!api || !options) return false;

    // 默认禁用 NNAPI：联想模型是动态形状（输出 [1, seq, 8000]，seq 随上下文增长），
    // nnapi-reference 后端在推理中会触发 SIGSEGV（tombstone: libonnxruntime.so 内
    // fault addr 0x600000002），导致 :inference 进程死亡、联想永久失效。
    // int8 动态量化模型在 CPU 上推理耗时仅毫秒级，NNAPI 收益可忽略。
    // 调试需要时设置系统属性 setprop debug.xime.nnapi 1 重新启用。
    char value[2] = {0};
    if (__system_property_get("debug.xime.nnapi", value) == 0 || value[0] != '1') {
        LOGD("NNAPI EP disabled by default (debug.xime.nnapi not set)");
        return false;
    }

    typedef OrtStatus* (*NnapiProviderFn)(OrtSessionOptions*, uint32_t);
    NnapiProviderFn nnapi_fn = (NnapiProviderFn)dlsym(
        RTLD_DEFAULT, "OrtSessionOptionsAppendExecutionProvider_Nnapi");

    if (!nnapi_fn) {
        LOGD("NNAPI EP not available in this libonnxruntime.so (CPU-only build)");
        return false;
    }

    uint32_t nnapi_flags = 0;
    nnapi_flags |= 0x001;  // NNAPI_FLAG_USE_FP16

    OrtStatus* status = nnapi_fn(options, nnapi_flags);
    if (status) {
        LOGW("NNAPI EP initialization failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return false;
    }

    LOGD("NNAPI execution provider enabled successfully");
    return true;
}

bool OnnxTryEnableCpuFallback(OrtSessionOptions* options) {
    const OrtApi* api = OnnxGetApi();
    if (!api || !options) return false;

    typedef OrtStatus* (*CpuProviderFn)(OrtSessionOptions*, int);
    CpuProviderFn cpu_fn = (CpuProviderFn)dlsym(
        RTLD_DEFAULT, "OrtSessionOptionsAppendExecutionProvider_CPU");

    if (!cpu_fn) {
        LOGD("CPU EP symbol not found (relying on built-in default fallback)");
        return false;
    }

    OrtStatus* status = cpu_fn(options, 1);  // use CPU arena
    if (status) {
        LOGW("CPU EP append failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return false;
    }

    LOGD("CPU execution provider appended as fallback");
    return true;
}
