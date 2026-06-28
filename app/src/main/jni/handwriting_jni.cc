#include <jni.h>
#include <android/log.h>
#include <onnxruntime_c_api.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <cmath>

#define LOG_TAG "HandwritingJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static OrtEnv* g_hw_env = nullptr;
static OrtSession* g_hw_session = nullptr;
static OrtAllocator* g_hw_allocator = nullptr;
static std::mutex g_hw_mutex;

static char g_hw_input_name[256] = {0};
static char g_hw_mask_name[256] = {0};
static char g_hw_output_name[256] = {0};

static constexpr int FIXED_LEN = 200;
static constexpr int FEAT_DIM = 5;

static const OrtApi* GetApi() {
    static const OrtApi* api = nullptr;
    if (!api) {
        api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!api) {
            LOGE("Failed to get ONNX Runtime API");
        }
    }
    return api;
}

static void QueryIONames() {
    const OrtApi* api = GetApi();
    if (!api || !g_hw_session) return;

    OrtStatus* status = nullptr;
    OrtAllocator* allocator = g_hw_allocator;

    size_t input_count = 0;
    status = api->SessionGetInputCount(g_hw_session, &input_count);
    if (status) {
        LOGE("Failed to get input count: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return;
    }
    LOGD("Input count: %zu", input_count);

    for (size_t i = 0; i < input_count; i++) {
        char* name = nullptr;
        status = api->SessionGetInputName(g_hw_session, i, allocator, &name);
        if (status) {
            LOGE("Failed to get input %zu name: %s", i, api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            continue;
        }
        LOGD("Input %zu name: %s", i, name);

        OrtTypeInfo* type_info = nullptr;
        status = api->SessionGetInputTypeInfo(g_hw_session, i, &type_info);
        if (status) {
            LOGE("Failed to get input %zu type info: %s", i, api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            api->AllocatorFree(allocator, name);
            continue;
        }

        const OrtTensorTypeAndShapeInfo* tensor_info = nullptr;
        status = api->CastTypeInfoToTensorInfo(type_info, &tensor_info);
        if (!status) {
            ONNXTensorElementDataType elem_type;
            api->GetTensorElementType(tensor_info, &elem_type);

            if (elem_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && i == 0) {
                strncpy(g_hw_input_name, name, sizeof(g_hw_input_name) - 1);
                LOGI("Found data input: %s", g_hw_input_name);
            } else if (elem_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL) {
                strncpy(g_hw_mask_name, name, sizeof(g_hw_mask_name) - 1);
                LOGI("Found mask input: %s", g_hw_mask_name);
            } else if (elem_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && i > 0) {
                strncpy(g_hw_mask_name, name, sizeof(g_hw_mask_name) - 1);
                LOGI("Fallback mask input: %s (float)", g_hw_mask_name);
            }
        }

        if (type_info) api->ReleaseTypeInfo(type_info);
        api->AllocatorFree(allocator, name);
    }

    size_t output_count = 0;
    status = api->SessionGetOutputCount(g_hw_session, &output_count);
    if (status) {
        LOGE("Failed to get output count: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return;
    }
    LOGD("Output count: %zu", output_count);

    for (size_t i = 0; i < output_count; i++) {
        char* name = nullptr;
        status = api->SessionGetOutputName(g_hw_session, i, allocator, &name);
        if (status) {
            LOGE("Failed to get output %zu name: %s", i, api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            continue;
        }
        LOGD("Output %zu name: %s", i, name);
        strncpy(g_hw_output_name, name, sizeof(g_hw_output_name) - 1);
        api->AllocatorFree(allocator, name);
    }

    LOGI("QueryIONames done: input='%s', mask='%s', output='%s'",
         g_hw_input_name, g_hw_mask_name, g_hw_output_name);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_handwriting_HandwritingNativeEngine_nativeInitialize(
    JNIEnv* env, jobject thiz, jstring model_path) {

    std::lock_guard<std::mutex> lock(g_hw_mutex);

    const OrtApi* api = GetApi();
    if (!api) {
        LOGE("ONNX Runtime API not available");
        return JNI_FALSE;
    }

    if (g_hw_session) {
        LOGD("Handwriting session already initialized");
        return JNI_TRUE;
    }

    const char* modelPathStr = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing Handwriting ONNX model: %s", modelPathStr);

    OrtStatus* status = nullptr;

    if (!g_hw_env) {
        status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "handwriting", &g_hw_env);
        if (status) {
            LOGE("Failed to create OrtEnv: %s", api->GetErrorMessage(status));
            api->ReleaseStatus(status);
            env->ReleaseStringUTFChars(model_path, modelPathStr);
            return JNI_FALSE;
        }
    }

    status = api->GetAllocatorWithDefaultOptions(&g_hw_allocator);
    if (status) {
        LOGE("Failed to get allocator: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    OrtSessionOptions* session_options = nullptr;
    status = api->CreateSessionOptions(&session_options);
    if (status) {
        LOGE("Failed to create session options: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    status = api->SetIntraOpNumThreads(session_options, 2);
    if (status) {
        LOGE("Failed to set intra op num threads: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseSessionOptions(session_options);
        env->ReleaseStringUTFChars(model_path, modelPathStr);
        return JNI_FALSE;
    }

    status = api->CreateSession(g_hw_env, modelPathStr, session_options, &g_hw_session);
    api->ReleaseSessionOptions(session_options);
    env->ReleaseStringUTFChars(model_path, modelPathStr);

    if (status) {
        LOGE("Failed to create session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return JNI_FALSE;
    }

    QueryIONames();

    if (g_hw_input_name[0] == '\0' || g_hw_output_name[0] == '\0') {
        LOGE("Failed to query model IO names");
        api->ReleaseSession(g_hw_session);
        g_hw_session = nullptr;
        return JNI_FALSE;
    }

    LOGI("Handwriting ONNX model initialized successfully");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_handwriting_HandwritingNativeEngine_nativePredict(
    JNIEnv* env, jobject thiz, jfloatArray stroke_data, jbyteArray mask_data, jint top_k) {

    std::lock_guard<std::mutex> lock(g_hw_mutex);

    const OrtApi* api = GetApi();
    if (!api || !g_hw_session) {
        LOGE("Handwriting ONNX Runtime not initialized");
        return nullptr;
    }

    if (top_k <= 0) top_k = 10;

    // ── Get input data ──
    jsize stroke_len = env->GetArrayLength(stroke_data);
    jfloat* stroke_elems = env->GetFloatArrayElements(stroke_data, nullptr);

    jsize mask_len = env->GetArrayLength(mask_data);
    jbyte* mask_elems = env->GetByteArrayElements(mask_data, nullptr);

    // ── Input shapes ──
    int64_t input_shape[3] = {1, FIXED_LEN, FEAT_DIM};
    int64_t mask_shape[2] = {1, FIXED_LEN};

    OrtMemoryInfo* memory_info = nullptr;
    OrtStatus* status = api->CreateMemoryInfo("Cpu", OrtArenaAllocator, 0, OrtMemTypeDefault, &memory_info);
    if (status) {
        LOGE("Failed to create memory info: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        env->ReleaseFloatArrayElements(stroke_data, stroke_elems, JNI_ABORT);
        env->ReleaseByteArrayElements(mask_data, mask_elems, JNI_ABORT);
        return nullptr;
    }

    // ── Create input tensor (float32, shape [1, 200, 5]) ──
    OrtValue* input_tensor = nullptr;
    status = api->CreateTensorWithDataAsOrtValue(
        memory_info,
        stroke_elems,
        stroke_len * sizeof(jfloat),
        input_shape, 3,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
        &input_tensor
    );
    if (status) {
        LOGE("Failed to create input tensor: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseMemoryInfo(memory_info);
        env->ReleaseFloatArrayElements(stroke_data, stroke_elems, JNI_ABORT);
        env->ReleaseByteArrayElements(mask_data, mask_elems, JNI_ABORT);
        return nullptr;
    }

    // ── Create mask tensor (bool, shape [1, 200]) ──
    OrtValue* mask_tensor = nullptr;
    // bool in ONNX is 1 byte per element, same as jbyte
    status = api->CreateTensorWithDataAsOrtValue(
        memory_info,
        mask_elems,
        mask_len * sizeof(jbyte),
        mask_shape, 2,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL,
        &mask_tensor
    );
    api->ReleaseMemoryInfo(memory_info);

    if (status) {
        LOGE("Failed to create mask tensor: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(input_tensor);
        env->ReleaseFloatArrayElements(stroke_data, stroke_elems, JNI_ABORT);
        env->ReleaseByteArrayElements(mask_data, mask_elems, JNI_ABORT);
        return nullptr;
    }

    // ── Run inference ──
    const char* input_names[] = {g_hw_input_name, g_hw_mask_name};
    const char* output_names[] = {g_hw_output_name};
    const OrtValue* input_tensors[] = {input_tensor, mask_tensor};

    OrtValue* output_tensor = nullptr;
    status = api->Run(g_hw_session, nullptr, input_names, input_tensors, 2,
                      output_names, 1, &output_tensor);

    api->ReleaseValue(input_tensor);
    api->ReleaseValue(mask_tensor);
    env->ReleaseFloatArrayElements(stroke_data, stroke_elems, JNI_ABORT);
    env->ReleaseByteArrayElements(mask_data, mask_elems, JNI_ABORT);

    if (status) {
        LOGE("Failed to run session: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return nullptr;
    }

    // ── Get output shape ──
    OrtTensorTypeAndShapeInfo* output_info = nullptr;
    status = api->GetTensorTypeAndShape(output_tensor, &output_info);
    if (status) {
        LOGE("Failed to get output info: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    size_t dims_count = 0;
    api->GetDimensionsCount(output_info, &dims_count);
    std::vector<int64_t> output_dims(dims_count);
    status = api->GetDimensions(output_info, output_dims.data(), dims_count);
    api->ReleaseTensorTypeAndShapeInfo(output_info);

    if (status) {
        LOGE("Failed to get dimensions: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    LOGD("Output shape: [%ld]", (long)output_dims[0]);
    for (size_t i = 0; i < dims_count; i++) {
        LOGD("  dim[%zu] = %ld", i, (long)output_dims[i]);
    }

    int64_t num_classes = output_dims[dims_count - 1];

    // ── Get output data ──
    float* logits = nullptr;
    status = api->GetTensorMutableData(output_tensor, (void**)&logits);
    if (status) {
        LOGE("Failed to get output data: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        api->ReleaseValue(output_tensor);
        return nullptr;
    }

    // ── Softmax ──
    float max_logit = logits[0];
    for (int64_t i = 1; i < num_classes; i++) {
        if (logits[i] > max_logit) max_logit = logits[i];
    }

    std::vector<float> probs(num_classes);
    float sum_exp = 0.0f;
    for (int64_t i = 0; i < num_classes; i++) {
        probs[i] = expf(logits[i] - max_logit);
        sum_exp += probs[i];
    }
    for (int64_t i = 0; i < num_classes; i++) {
        probs[i] /= sum_exp;
    }

    // ── Top-K ──
    std::vector<std::pair<int, float>> scores;
    scores.reserve(num_classes);
    for (int64_t i = 0; i < num_classes; i++) {
        scores.emplace_back(static_cast<int>(i), probs[i]);
    }

    std::partial_sort(scores.begin(), scores.begin() + std::min((int64_t)top_k, num_classes), scores.end(),
        [](const auto& a, const auto& b) { return a.second > b.second; });

    int64_t k = std::min((int64_t)top_k, num_classes);

    // ── Build result array ──
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(k * 2, string_class, nullptr);

    for (int64_t i = 0; i < k; i++) {
        char idx_str[32];
        snprintf(idx_str, sizeof(idx_str), "%d", scores[i].first);
        jstring j_idx = env->NewStringUTF(idx_str);
        env->SetObjectArrayElement(result, i * 2, j_idx);

        char score_str[32];
        snprintf(score_str, sizeof(score_str), "%f", scores[i].second);
        jstring j_score = env->NewStringUTF(score_str);
        env->SetObjectArrayElement(result, i * 2 + 1, j_score);
    }

    api->ReleaseValue(output_tensor);

    LOGD("Predicted %lld candidates", (long long)k);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_handwriting_HandwritingNativeEngine_nativeRelease(
    JNIEnv* env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_hw_mutex);

    const OrtApi* api = GetApi();

    if (g_hw_session) {
        api->ReleaseSession(g_hw_session);
        g_hw_session = nullptr;
        LOGD("Handwriting session released");
    }

    if (g_hw_env) {
        api->ReleaseEnv(g_hw_env);
        g_hw_env = nullptr;
        LOGD("Handwriting env released");
    }

    g_hw_input_name[0] = '\0';
    g_hw_mask_name[0] = '\0';
    g_hw_output_name[0] = '\0';
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_handwriting_HandwritingNativeEngine_nativeIsInitialized(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_hw_mutex);
    return g_hw_session ? JNI_TRUE : JNI_FALSE;
}
