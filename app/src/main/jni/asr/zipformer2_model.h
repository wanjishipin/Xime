// Self-implemented streaming zipformer2 transducer model.
//
// Reference algorithm: sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx),
// Apache-2.0. This is an independent implementation on top of the project's
// onnxruntime (v1.28) C++ API.
#pragma once

#include <onnxruntime_cxx_api.h>

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace xime_asr {

struct AsrModelPaths {
  std::string encoder;  // encoder.int8.onnx
  std::string decoder;  // decoder.onnx
  std::string joiner;   // joiner.int8.onnx
};

// Streaming zipformer2 transducer model: loads encoder/decoder/joiner ONNX
// graphs, reads structural metadata from the encoder graph, and exposes
// encoder/decoder/joiner inference plus encoder recurrent-state management.
class Zipformer2Model {
 public:
  explicit Zipformer2Model(const AsrModelPaths &paths);
  ~Zipformer2Model();

  // ---- encoder ----
  // features: (N, T, feat_dim) float; states: encoder recurrent state.
  // Returns {encoder_out (N, T', joiner_dim), next_states}.
  std::pair<Ort::Value, std::vector<Ort::Value>> RunEncoder(
      Ort::Value features, std::vector<Ort::Value> states,
      Ort::Value processed_frames);

  std::vector<Ort::Value> GetEncoderInitStates();

  std::vector<Ort::Value> StackStates(
      const std::vector<std::vector<Ort::Value>> &states) const;

  std::vector<std::vector<Ort::Value>> UnStackStates(
      const std::vector<Ort::Value> &states) const;

  // ---- decoder ----
  Ort::Value RunDecoder(Ort::Value decoder_input);

  // ---- joiner ----
  Ort::Value RunJoiner(Ort::Value encoder_out, Ort::Value decoder_out);

  // ---- model params ----
  int32_t ContextSize() const { return context_size_; }
  int32_t ChunkSize() const { return T_; }
  int32_t ChunkShift() const { return decode_chunk_len_; }
  int32_t VocabSize() const { return vocab_size_; }
  int32_t SubsamplingFactor() const { return 4; }
  bool UseWhisperFeature() const { return use_whisper_feature_; }
  void SetFeatureDim(int32_t d) { feature_dim_ = d; }

  OrtAllocator *Allocator() { return allocator_; }

  // Build decoder input (N, context_size) from token sequences.
  Ort::Value BuildDecoderInput(
      const std::vector<std::vector<int64_t>> &token_seqs);

 private:
  void LoadEncoderSession(const std::string &path);
  void LoadDecoderSession(const std::string &path);
  void LoadJoinerSession(const std::string &path);

  void ReadEncoderMetadata();
  void ReadDecoderMetadata();

  std::vector<std::string> GetInputNames(const Ort::UnownedSession *sess) const;
  std::vector<std::string> GetOutputNames(const Ort::UnownedSession *sess) const;

  Ort::SessionOptions encoder_sess_opts_;
  Ort::SessionOptions decoder_sess_opts_;
  Ort::SessionOptions joiner_sess_opts_;

  // 非拥有包装：session 由共享 env 创建，原始指针在此析构时显式释放。
  Ort::UnownedSession encoder_sess_;
  Ort::UnownedSession decoder_sess_;
  Ort::UnownedSession joiner_sess_;

  std::vector<std::string> encoder_input_names_;
  std::vector<std::string> encoder_output_names_;
  std::vector<std::string> decoder_input_names_;
  std::vector<std::string> decoder_output_names_;
  std::vector<std::string> joiner_input_names_;
  std::vector<std::string> joiner_output_names_;

  Ort::AllocatorWithDefaultOptions allocator_;

  // Encoder structural metadata (read from ONNX custom metadata).
  std::vector<int32_t> encoder_dims_;
  std::vector<int32_t> query_head_dims_;
  std::vector<int32_t> value_head_dims_;
  std::vector<int32_t> num_heads_;
  std::vector<int32_t> num_encoder_layers_;
  std::vector<int32_t> cnn_module_kernels_;
  std::vector<int32_t> left_context_len_;

  int32_t T_ = 0;
  int32_t decode_chunk_len_ = 0;

  int32_t context_size_ = 0;
  int32_t vocab_size_ = 0;
  int32_t feature_dim_ = 80;

  bool use_whisper_feature_ = false;
};

}  // namespace xime_asr
