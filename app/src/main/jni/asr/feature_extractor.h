// Streaming feature extractor using kaldi-native-fbank (Apache-2.0).
//
// Supports both kaldi fbank and whisper-style log-mel features.
// The zipformer2 model declares which feature it expects via its ONNX
// metadata ("feature: whisper" or kaldi fbank); the recognizer selects the
// matching extractor and applies the required per-chunk normalization.
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "kaldi-native-fbank/csrc/online-feature.h"

namespace xime_asr {

class FeatureExtractor {
 public:
  enum class Type {
    kFbank,
    kWhisper,
  };

  explicit FeatureExtractor(Type type = Type::kFbank);

  // Feed normalized float samples (in [-1, 1]) sampled at 16 kHz.
  void AcceptWaveform(const float *samples, int32_t n);
  void InputFinished();

  int32_t NumFramesReady() const;
  // Pointer to the raw feature frame at index `frame` (FeatureDim() floats).
  // For whisper features this is the linear mel power; apply
  // NormalizeFrame() before feeding the model.
  const float *GetFrame(int32_t frame) const;
  // Discard the first `n` frames.
  void Pop(int32_t n);

  int32_t FeatureDim() const;
  float FrameShiftInSeconds() const;
  Type type() const { return type_; }

  // whisper log-mel normalization: log10(clip(x, 1e-10)), then clamp to
  // (max - 8), then (x + 4) / 4. `frame` is a FeatureDim()-sized buffer
  // from GetFrame(); `max_log` must be the max over the whole chunk being
  // fed to the model (computed by the caller over all chunk frames).
  static void NormalizeWhisperFrame(float *frame, int32_t dim,
                                    float max_log10);

  // Helper: log10(clip(x, 1e-10)) of a single value.
  static float WhisperLog(float x);

 private:
  Type type_;
  std::unique_ptr<knf::OnlineFbank> fbank_;
  std::unique_ptr<knf::OnlineWhisperFbank> whisper_fbank_;
};

}  // namespace xime_asr
