// Streaming zipformer2 ASR recognizer (greedy search).
//
// Reference algorithm: sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx),
// Apache-2.0. Independent implementation.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <onnxruntime_cxx_api.h>

#include "feature_extractor.h"
#include "zipformer2_model.h"

namespace xime_asr {

// Streaming greedy-search recognizer for a single utterance.
class StreamingRecognizer {
 public:
  StreamingRecognizer(const AsrModelPaths &paths,
                      const std::string &tokens_path);

  bool LoadOk() const { return loaded_; }

  // Start a new utterance.
  void Reset();

  // Feed normalized float samples ([-1,1]) at 16 kHz, then decode available
  // chunks. Returns current partial text.
  std::string AcceptPcm(const float *samples, int32_t n);

  // Flush remaining audio and return the final text.
  std::string Finalize();

  // Current partial text without consuming new audio.
  std::string GetPartialText() const;

 private:
  void DecodeAvailableChunks();
  void DecodeOneChunk();
  std::string ConvertTokens() const;
  int64_t UnkId() const;

  std::unique_ptr<Zipformer2Model> model_;
  std::unique_ptr<FeatureExtractor> feat_;

  std::vector<int64_t> tokens_;
  Ort::Value decoder_out_;
  bool has_decoder_out_ = false;

  std::vector<Ort::Value> states_;

  int32_t processed_frames_ = 0;
  int32_t chunk_size_ = 0;
  int32_t chunk_shift_ = 0;
  int32_t feature_dim_ = 80;
  int32_t context_size_ = 0;
  bool use_whisper_feature_ = false;

  std::vector<std::string> id2sym_;
  bool loaded_ = false;
};

}  // namespace xime_asr
