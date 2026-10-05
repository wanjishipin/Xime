// JNI bridge for the self-implemented streaming zipformer2 ASR.
//
// Reference algorithm: sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx),
// Apache-2.0. Feature extraction: kaldi-native-fbank (Apache-2.0).
#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "streaming_recognizer.h"

#define LOG_TAG "AsrJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using xime_asr::AsrModelPaths;
using xime_asr::StreamingRecognizer;

namespace {

struct RecognizerHolder {
  std::unique_ptr<StreamingRecognizer> rec;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeCreate(JNIEnv *env, jobject,
                                                        jstring jEncoder,
                                                        jstring jDecoder,
                                                        jstring jJoiner,
                                                        jstring jTokens) {
  const char *enc = env->GetStringUTFChars(jEncoder, nullptr);
  const char *dec = env->GetStringUTFChars(jDecoder, nullptr);
  const char *join = env->GetStringUTFChars(jJoiner, nullptr);
  const char *tok = env->GetStringUTFChars(jTokens, nullptr);

  AsrModelPaths paths;
  paths.encoder = enc;
  paths.decoder = dec;
  paths.joiner = join;
  std::string tokens = tok;

  env->ReleaseStringUTFChars(jEncoder, enc);
  env->ReleaseStringUTFChars(jDecoder, dec);
  env->ReleaseStringUTFChars(jJoiner, join);
  env->ReleaseStringUTFChars(jTokens, tok);

  auto *holder = new (std::nothrow) RecognizerHolder();
  if (!holder) return 0;
  {
    using clock = std::chrono::steady_clock;
    auto t0 = clock::now();
    holder->rec = std::make_unique<StreamingRecognizer>(paths, tokens);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  clock::now() - t0)
                  .count();
    LOGE("nativeCreate: model load took %lld ms", (long long)ms);
    if (!holder->rec->LoadOk()) {
      LOGE("nativeCreate: failed to load ASR model");
      delete holder;
      return 0;
    }
  }
  holder->rec->Reset();
  return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeReset(JNIEnv * /*env*/,
                                                       jobject, jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  if (holder && holder->rec) holder->rec->Reset();
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeAcceptPcm(JNIEnv *env,
                                                           jobject,
                                                           jlong handle,
                                                           jbyteArray pcm) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  if (!holder || !holder->rec) return;
  jsize n = env->GetArrayLength(pcm);
  if (n <= 0) return;
  jbyte *bytes = env->GetByteArrayElements(pcm, nullptr);

  // 自动增益（AGC）：弱录音信号放大到接近模型训练分布，
  // 避免"你/觉"等弱音开头因能量过低被识别为静音。
  // 特征提取（fbank/whisper）在 StreamingRecognizer 内部完成；
  // 这里仅做幅度归一化到 float 采样。
  constexpr double kTargetRms = 0.008;    // 目标 RMS（约 -42dBFS，float 域）
  constexpr double kMaxGain = 20.0;       // 增益上限，防止噪音被过度放大
  constexpr double kSilenceRms = 1000.0;  // 静音门限（int16 域）：低于该值
                                          // 视为静音，不放大，避免底噪放大

  std::vector<float> samples(static_cast<size_t>(n / 2));
  double sum_sq = 0.0;
  for (jsize i = 0; i + 1 < n; i += 2) {
    int16_t s = static_cast<int16_t>(static_cast<uint8_t>(bytes[i]) |
                                     (static_cast<uint8_t>(bytes[i + 1]) << 8));
    samples[static_cast<size_t>(i / 2)] = static_cast<float>(s);
    double v = static_cast<double>(s);
    sum_sq += v * v;
  }
  env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);

  const size_t cnt = samples.size();
  double rms = (cnt > 0) ? std::sqrt(sum_sq / static_cast<double>(cnt)) : 0.0;

  double agc = 1.0;
  if (rms > kSilenceRms) {
    double target_int16 = kTargetRms * 32768.0;
    agc = target_int16 / rms;
    if (agc > kMaxGain) agc = kMaxGain;
    if (agc < 1.0) agc = 1.0;  // 强信号不衰减
  }
  // 静音块 agc=1.0，仅保留基础增益，不放大底噪

  const float gain = static_cast<float>(agc);
  for (size_t i = 0; i < cnt; ++i) {
    float v = samples[i] * gain;
    if (v > 32767.0f) v = 32767.0f;
    else if (v < -32767.0f) v = -32767.0f;
    // 归一化到 [-1, 1]，whisper/fbank 特征都基于该范围
    samples[i] = v / 32768.0f;
  }

  holder->rec->AcceptPcm(samples.data(), static_cast<int32_t>(samples.size()));
}

JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeGetPartial(JNIEnv *env,
                                                            jobject,
                                                            jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  std::string text;
  if (holder && holder->rec) text = holder->rec->GetPartialText();
  return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeFinalize(JNIEnv *env, jobject,
                                                          jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  std::string text;
  if (holder && holder->rec) text = holder->rec->Finalize();
  return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeRelease(JNIEnv * /*env*/,
                                                         jobject, jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  if (holder) delete holder;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /*vm*/, void * /*reserved*/) {
  return JNI_VERSION_1_6;
}

}  // extern "C"
