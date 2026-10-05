// Streaming feature extractor using kaldi-native-fbank (Apache-2.0).
#include "feature_extractor.h"

#include <algorithm>
#include <cmath>

namespace xime_asr {

FeatureExtractor::FeatureExtractor(Type type) : type_(type) {
  if (type_ == Type::kWhisper) {
    knf::WhisperFeatureOptions opts;
    opts.dim = 80;
    whisper_fbank_ = std::make_unique<knf::OnlineWhisperFbank>(opts);
    return;
  }

  knf::FbankOptions opts;
  opts.frame_opts.samp_freq = 16000;
  opts.frame_opts.frame_shift_ms = 10;
  opts.frame_opts.frame_length_ms = 25;
  opts.frame_opts.dither = 0;
  opts.frame_opts.snip_edges = false;
  opts.frame_opts.window_type = "povey";
  opts.mel_opts.num_bins = 80;
  opts.mel_opts.low_freq = 20;
  // Negative high_freq = offset from Nyquist (KNF), matching sherpa/icefall.
  opts.mel_opts.high_freq = -400;
  fbank_ = std::make_unique<knf::OnlineFbank>(opts);
}

void FeatureExtractor::AcceptWaveform(const float *samples, int32_t n) {
  if (whisper_fbank_) {
    whisper_fbank_->AcceptWaveform(16000, samples, n);
    return;
  }
  fbank_->AcceptWaveform(16000, samples, n);
}

void FeatureExtractor::InputFinished() {
  if (whisper_fbank_) {
    whisper_fbank_->InputFinished();
    return;
  }
  fbank_->InputFinished();
}

int32_t FeatureExtractor::NumFramesReady() const {
  if (whisper_fbank_) return whisper_fbank_->NumFramesReady();
  return fbank_->NumFramesReady();
}

const float *FeatureExtractor::GetFrame(int32_t frame) const {
  if (whisper_fbank_) return whisper_fbank_->GetFrame(frame);
  return fbank_->GetFrame(frame);
}

void FeatureExtractor::Pop(int32_t n) {
  if (whisper_fbank_) {
    whisper_fbank_->Pop(n);
    return;
  }
  fbank_->Pop(n);
}

int32_t FeatureExtractor::FeatureDim() const {
  if (whisper_fbank_) return whisper_fbank_->Dim();
  return fbank_->Dim();
}

float FeatureExtractor::FrameShiftInSeconds() const {
  if (whisper_fbank_) return whisper_fbank_->FrameShiftInSeconds();
  return fbank_->FrameShiftInSeconds();
}

float FeatureExtractor::WhisperLog(float x) {
  // log10 with a floor of 1e-10, matching OpenAI whisper's log-mel.
  return std::log10(std::max(x, 1e-10f));
}

void FeatureExtractor::NormalizeWhisperFrame(float *frame, int32_t dim,
                                             float max_log10) {
  // log10(clip(x, 1e-10)); clamp dynamic range to (max - 8); then (x + 4) / 4.
  const float floor_log = max_log10 - 8.0f;
  for (int32_t i = 0; i < dim; ++i) {
    float v = std::log10(std::max(frame[i], 1e-10f));
    if (v < floor_log) v = floor_log;
    frame[i] = (v + 4.0f) / 4.0f;
  }
}

}  // namespace xime_asr
