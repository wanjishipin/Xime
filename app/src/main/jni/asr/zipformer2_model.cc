// Self-implemented streaming zipformer2 transducer model.
//
// Reference algorithm: sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx),
// Apache-2.0. Independent implementation using the project's onnxruntime v1.28.
#include "zipformer2_model.h"

#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <map>
#include <numeric>
#include <sstream>
#include <stdexcept>

#include "onnx_env.h"

#define LOG_TAG "Zipformer2Model"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace xime_asr {

namespace {

// ---- tensor helpers (float / int64) ----

size_t Numel(const std::vector<int64_t> &shape) {
  size_t n = 1;
  for (auto d : shape) n *= static_cast<size_t>(d);
  return n;
}

void Fill(Ort::Value *v, float val) {
  float *p = v->GetTensorMutableData<float>();
  size_t n = static_cast<size_t>(v->GetTensorTypeAndShapeInfo().GetElementCount());
  std::fill(p, p + n, val);
}

void Fill(Ort::Value *v, int64_t val) {
  int64_t *p = v->GetTensorMutableData<int64_t>();
  size_t n = static_cast<size_t>(v->GetTensorTypeAndShapeInfo().GetElementCount());
  std::fill(p, p + n, val);
}

// Concatenate tensors along `axis`. All inputs must share dtype/rank.
template <typename T>
Ort::Value CatT(OrtAllocator *alloc, const std::vector<Ort::Value *> &ins,
                int64_t axis) {
  auto shape0 = ins[0]->GetTensorTypeAndShapeInfo().GetShape();
  int64_t rank = static_cast<int64_t>(shape0.size());
  int64_t inner = 1, outer = 1;
  for (int64_t i = axis + 1; i < rank; ++i) inner *= shape0[i];
  for (int64_t i = 0; i < axis; ++i) outer *= shape0[i];

  int64_t sum_axis = 0;
  std::vector<const T *> srcs;
  std::vector<int64_t> axis_dims;
  for (auto *in : ins) {
    auto s = in->GetTensorTypeAndShapeInfo().GetShape();
    int64_t ad = s[axis];
    sum_axis += ad;
    axis_dims.push_back(ad);
    srcs.push_back(in->GetTensorData<T>());
  }

  std::vector<int64_t> out_shape = shape0;
  out_shape[axis] = sum_axis;
  Ort::Value out =
      Ort::Value::CreateTensor<T>(alloc, out_shape.data(), out_shape.size());
  T *dst = out.GetTensorMutableData<T>();
  const int64_t plane = inner;
  for (int64_t o = 0; o < outer; ++o) {
    size_t base = static_cast<size_t>(o) * sum_axis * plane;
    for (size_t k = 0; k < srcs.size(); ++k) {
      int64_t ad = axis_dims[k];
      const T *src = srcs[k];
      size_t sbase = static_cast<size_t>(o) * ad * plane;
      std::copy(src + sbase, src + sbase + ad * plane, dst + base);
      base += ad * plane;
    }
  }
  return out;
}

// Split a tensor into `n` pieces along `axis`, each of axis extent 1.
template <typename T>
std::vector<Ort::Value> UnbindT(OrtAllocator *alloc, const Ort::Value &in,
                                int64_t axis) {
  auto shape = in.GetTensorTypeAndShapeInfo().GetShape();
  int64_t rank = static_cast<int64_t>(shape.size());
  int64_t n = shape[axis];
  std::vector<int64_t> out_shape = shape;
  out_shape[axis] = 1;

  int64_t inner = 1, outer = 1;
  for (int64_t i = axis + 1; i < rank; ++i) inner *= shape[i];
  for (int64_t i = 0; i < axis; ++i) outer *= shape[i];
  const int64_t plane = inner;

  const T *src = in.GetTensorData<T>();
  std::vector<Ort::Value> out;
  out.reserve(static_cast<size_t>(n));
  for (int64_t i = 0; i < n; ++i) {
    Ort::Value v = Ort::Value::CreateTensor<T>(alloc, out_shape.data(),
                                               out_shape.size());
    T *dst = v.GetTensorMutableData<T>();
    for (int64_t o = 0; o < outer; ++o) {
      size_t sb = static_cast<size_t>(o) * n * plane +
                  static_cast<size_t>(i) * plane;
      std::copy(src + sb, src + sb + plane, dst + static_cast<size_t>(o) * plane);
    }
    out.push_back(std::move(v));
  }
  return out;
}

Ort::Value Cat(OrtAllocator *alloc, const std::vector<Ort::Value *> &ins,
               int64_t axis) {
  return CatT<float>(alloc, ins, axis);
}

}  // namespace

Zipformer2Model::Zipformer2Model(const AsrModelPaths &paths) : allocator_{} {
  // 纯 CPU 推理。zipformer2 为 int8 + 动态 shape 的流式模型，NNAPI 支持率
  // 不足 5%（且多为 CPU reference 驱动），切图开销大于收益，故不启用硬件 EP。
  OnnxGetApi();  // 初始化 C API

  // 复用全局共享 env 的 intra-op 线程池（2 线程），而非每个 session 自建
  // 线程池。此前 3 个 session × 4 线程 = 12 个常驻线程，是模型驻留期间
  // 待机发热的主要来源（线程池空闲时仍周期性 spin-wait）。
  //
  // 模型已带 int8 权重发布，图优化空间有限；用 ORT_ENABLE_BASIC 而非默认
  // ORT_ENABLE_ALL 可显著缩短 CreateSession 耗时（模型被空闲释放后重新
  // 加载时，加载延迟直接决定语音开头是否丢失）。
  auto configure = [](Ort::SessionOptions &opts) {
    OnnxTryEnableCpuFallback(opts);
    opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_BASIC);
  };
  configure(encoder_sess_opts_);
  configure(decoder_sess_opts_);
  configure(joiner_sess_opts_);

  LoadEncoderSession(paths.encoder);
  LoadDecoderSession(paths.decoder);
  LoadJoinerSession(paths.joiner);
}

void Zipformer2Model::LoadEncoderSession(const std::string &path) {
  const OrtApi *api = OnnxGetApi();
  OrtEnv *env = OnnxGetSharedEnv();
  if (!api || !env) {
    throw std::runtime_error("ONNX Runtime env not available");
  }
  OrtSession *raw = nullptr;
  OrtStatus *status = api->CreateSession(env, path.c_str(), encoder_sess_opts_, &raw);
  if (status) {
    LOGE("Failed to create encoder session: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    throw std::runtime_error("Create encoder session failed");
  }
  encoder_sess_ = Ort::UnownedSession{raw};
  encoder_input_names_ = GetInputNames(&encoder_sess_);
  encoder_output_names_ = GetOutputNames(&encoder_sess_);
  ReadEncoderMetadata();
}

void Zipformer2Model::LoadDecoderSession(const std::string &path) {
  const OrtApi *api = OnnxGetApi();
  OrtEnv *env = OnnxGetSharedEnv();
  if (!api || !env) {
    throw std::runtime_error("ONNX Runtime env not available");
  }
  OrtSession *raw = nullptr;
  OrtStatus *status = api->CreateSession(env, path.c_str(), decoder_sess_opts_, &raw);
  if (status) {
    LOGE("Failed to create decoder session: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    throw std::runtime_error("Create decoder session failed");
  }
  decoder_sess_ = Ort::UnownedSession{raw};
  decoder_input_names_ = GetInputNames(&decoder_sess_);
  decoder_output_names_ = GetOutputNames(&decoder_sess_);
  ReadDecoderMetadata();
}

void Zipformer2Model::LoadJoinerSession(const std::string &path) {
  const OrtApi *api = OnnxGetApi();
  OrtEnv *env = OnnxGetSharedEnv();
  if (!api || !env) {
    throw std::runtime_error("ONNX Runtime env not available");
  }
  OrtSession *raw = nullptr;
  OrtStatus *status = api->CreateSession(env, path.c_str(), joiner_sess_opts_, &raw);
  if (status) {
    LOGE("Failed to create joiner session: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    throw std::runtime_error("Create joiner session failed");
  }
  joiner_sess_ = Ort::UnownedSession{raw};
  joiner_input_names_ = GetInputNames(&joiner_sess_);
  joiner_output_names_ = GetOutputNames(&joiner_sess_);
}

Zipformer2Model::~Zipformer2Model() {
  const OrtApi *api = OnnxGetApi();
  if (!api) return;
  if (encoder_sess_) api->ReleaseSession(encoder_sess_);
  if (decoder_sess_) api->ReleaseSession(decoder_sess_);
  if (joiner_sess_) api->ReleaseSession(joiner_sess_);
}

std::vector<std::string> Zipformer2Model::GetInputNames(
    const Ort::UnownedSession *sess) const {
  std::vector<std::string> names;
  size_t n = sess->GetInputCount();
  names.reserve(n);
  for (size_t i = 0; i < n; ++i) {
    auto name = sess->GetInputNameAllocated(i, allocator_);
    names.emplace_back(name.get());
  }
  return names;
}

std::vector<std::string> Zipformer2Model::GetOutputNames(
    const Ort::UnownedSession *sess) const {
  std::vector<std::string> names;
  size_t n = sess->GetOutputCount();
  names.reserve(n);
  for (size_t i = 0; i < n; ++i) {
    auto name = sess->GetOutputNameAllocated(i, allocator_);
    names.emplace_back(name.get());
  }
  return names;
}

void Zipformer2Model::ReadEncoderMetadata() {
  auto meta = encoder_sess_.GetModelMetadata();
  // 键可能缺失（如非 whisper 模型没有 "feature"）；LookupCustomMetadataMapAllocated
  // 返回 nullptr 时构造 std::string 会段错误，必须判空。
  auto lookup = [&](const char *key) -> std::string {
    auto v = meta.LookupCustomMetadataMapAllocated(key, allocator_);
    return v ? std::string(v.get()) : std::string();
  };

  auto read_vec = [&](const char *key) {
    std::vector<int32_t> v;
    std::istringstream ss(lookup(key));
    int32_t x;
    while (ss >> x) v.push_back(x);
    return v;
  };
  auto read_int = [&](const char *key, int32_t def) {
    auto s = lookup(key);
    if (s.empty()) return def;
    return std::stoi(s);
  };

  encoder_dims_ = read_vec("encoder_dims");
  query_head_dims_ = read_vec("query_head_dims");
  value_head_dims_ = read_vec("value_head_dims");
  num_heads_ = read_vec("num_heads");
  num_encoder_layers_ = read_vec("num_encoder_layers");
  cnn_module_kernels_ = read_vec("cnn_module_kernels");
  left_context_len_ = read_vec("left_context_len");
  T_ = read_int("T", 0);
  decode_chunk_len_ = read_int("decode_chunk_len", 0);

  // 流式解码按 (chunk_size=T, chunk_shift=decode_chunk_len) 推进，缺键或
  // 非正值会导致解码循环退化；此处显式失败，让上层走"模型不兼容"的优雅路径。
  if (T_ <= 0 || decode_chunk_len_ <= 0) {
    throw std::runtime_error("encoder metadata missing T/decode_chunk_len");
  }

  if (lookup("feature") == "whisper") use_whisper_feature_ = true;
}

void Zipformer2Model::ReadDecoderMetadata() {
  auto meta = decoder_sess_.GetModelMetadata();
  // 同 ReadEncoderMetadata：键缺失时必须返回空串而非 nullptr 构造。
  auto lookup = [&](const char *key) -> std::string {
    auto v = meta.LookupCustomMetadataMapAllocated(key, allocator_);
    return v ? std::string(v.get()) : std::string();
  };
  auto read_int = [&](const char *key, int32_t def) {
    auto s = lookup(key);
    if (s.empty()) return def;
    return std::stoi(s);
  };
  vocab_size_ = read_int("vocab_size", 0);
  context_size_ = read_int("context_size", 0);
}

std::pair<Ort::Value, std::vector<Ort::Value>>
Zipformer2Model::RunEncoder(Ort::Value features, std::vector<Ort::Value> states,
                            Ort::Value /*processed_frames*/) {
  std::vector<const char *> in_names;
  in_names.reserve(encoder_input_names_.size());
  for (auto &s : encoder_input_names_) in_names.push_back(s.c_str());
  std::vector<const char *> out_names;
  for (auto &s : encoder_output_names_) out_names.push_back(s.c_str());

  std::vector<Ort::Value> in_vals;
  in_vals.reserve(1 + states.size());
  in_vals.push_back(std::move(features));
  for (auto &v : states) in_vals.push_back(std::move(v));

  auto out = encoder_sess_.Run(Ort::RunOptions{nullptr}, in_names.data(),
                                in_vals.data(), in_vals.size(),
                                out_names.data(), out_names.size());

  std::vector<Ort::Value> next_states;
  next_states.reserve(out.size() - 1);
  for (size_t i = 1; i < out.size(); ++i) next_states.push_back(std::move(out[i]));
  return {std::move(out[0]), std::move(next_states)};
}

Ort::Value Zipformer2Model::RunDecoder(Ort::Value decoder_input) {
  std::vector<const char *> in_names;
  for (auto &s : decoder_input_names_) in_names.push_back(s.c_str());
  std::vector<const char *> out_names;
  for (auto &s : decoder_output_names_) out_names.push_back(s.c_str());
  auto out = decoder_sess_.Run(Ort::RunOptions{nullptr}, in_names.data(),
                                &decoder_input, 1, out_names.data(),
                                out_names.size());
  return std::move(out[0]);
}

Ort::Value Zipformer2Model::RunJoiner(Ort::Value encoder_out,
                                      Ort::Value decoder_out) {
  std::vector<const char *> in_names;
  for (auto &s : joiner_input_names_) in_names.push_back(s.c_str());
  std::vector<const char *> out_names;
  for (auto &s : joiner_output_names_) out_names.push_back(s.c_str());

  // Order input values to match the model's declared input name order.
  const bool enc_first =
      joiner_input_names_[0].find("encoder") != std::string::npos;
  std::vector<Ort::Value> in_vals;
  in_vals.reserve(2);
  if (enc_first) {
    in_vals.push_back(std::move(encoder_out));
    in_vals.push_back(std::move(decoder_out));
  } else {
    in_vals.push_back(std::move(decoder_out));
    in_vals.push_back(std::move(encoder_out));
  }
  auto out = joiner_sess_.Run(Ort::RunOptions{nullptr}, in_names.data(),
                               in_vals.data(), in_vals.size(), out_names.data(),
                               out_names.size());
  return std::move(out[0]);
}

std::vector<Ort::Value> Zipformer2Model::GetEncoderInitStates() {
  std::vector<Ort::Value> ans;
  // 第一个 encoder 输入是特征 x，其余为状态。逐个按模型声明的期望形状
  // 生成零状态（float/int64 依元素类型），避免对模型输入顺序/形状的硬编码。
  for (size_t i = 1; i < encoder_input_names_.size(); ++i) {
    auto info = encoder_sess_.GetInputTypeInfo(i).GetTensorTypeAndShapeInfo();
    auto shape = info.GetShape();
    for (auto &d : shape) {
      if (d == -1) d = 1;  // dynamic dim -> 1
    }
    ONNXTensorElementDataType type = info.GetElementType();
    if (type == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
      auto v = Ort::Value::CreateTensor<int64_t>(allocator_, shape.data(),
                                                 shape.size());
      Fill(&v, static_cast<int64_t>(0));
      ans.push_back(std::move(v));
    } else {
      auto v = Ort::Value::CreateTensor<float>(allocator_, shape.data(),
                                               shape.size());
      Fill(&v, 0.f);
      ans.push_back(std::move(v));
    }
  }
  return ans;
}

std::vector<Ort::Value> Zipformer2Model::StackStates(
    const std::vector<std::vector<Ort::Value>> &states) const {
  OrtAllocator *alloc = const_cast<Zipformer2Model *>(this)->Allocator();
  int32_t batch_size = static_cast<int32_t>(states.size());
  std::vector<Ort::Value> ans;
  int32_t num_states = static_cast<int32_t>(states[0].size());
  ans.reserve(num_states);

  for (int32_t i = 0; i < (num_states - 2) / 6; ++i) {
    std::vector<Ort::Value *> refs;
    for (int32_t n = 0; n < batch_size; ++n) {
      refs.push_back(const_cast<Ort::Value *>(&states[n][6 * i]));
    }
    ans.push_back(Cat(alloc, refs, 1));
    refs.clear();
    for (int32_t n = 0; n < batch_size; ++n) {
      refs.push_back(const_cast<Ort::Value *>(&states[n][6 * i + 1]));
    }
    ans.push_back(Cat(alloc, refs, 1));
    refs.clear();
    for (int32_t n = 0; n < batch_size; ++n) {
      refs.push_back(const_cast<Ort::Value *>(&states[n][6 * i + 2]));
    }
    ans.push_back(Cat(alloc, refs, 1));
    refs.clear();
    for (int32_t n = 0; n < batch_size; ++n) {
      refs.push_back(const_cast<Ort::Value *>(&states[n][6 * i + 3]));
    }
    ans.push_back(Cat(alloc, refs, 1));
    refs.clear();
    for (int32_t n = 0; n < batch_size; ++n) {
      refs.push_back(const_cast<Ort::Value *>(&states[n][6 * i + 4]));
    }
    ans.push_back(Cat(alloc, refs, 0));
    refs.clear();
    for (int32_t n = 0; n < batch_size; ++n) {
      refs.push_back(const_cast<Ort::Value *>(&states[n][6 * i + 5]));
    }
    ans.push_back(Cat(alloc, refs, 0));
  }

  std::vector<Ort::Value *> refs;
  for (int32_t n = 0; n < batch_size; ++n) {
    refs.push_back(const_cast<Ort::Value *>(&states[n][num_states - 2]));
  }
  ans.push_back(Cat(alloc, refs, 0));
  refs.clear();
  for (int32_t n = 0; n < batch_size; ++n) {
    refs.push_back(const_cast<Ort::Value *>(&states[n][num_states - 1]));
  }
  ans.push_back(CatT<int64_t>(alloc, refs, 0));
  return ans;
}

std::vector<std::vector<Ort::Value>> Zipformer2Model::UnStackStates(
    const std::vector<Ort::Value> &states) const {
  OrtAllocator *alloc = const_cast<Zipformer2Model *>(this)->Allocator();
  int32_t m = std::accumulate(num_encoder_layers_.begin(),
                              num_encoder_layers_.end(), 0);
  int32_t batch_size =
      static_cast<int32_t>(states[0].GetTensorTypeAndShapeInfo().GetShape()[1]);
  std::vector<std::vector<Ort::Value>> ans(batch_size);

  for (int32_t i = 0; i < m; ++i) {
    std::vector<int64_t> axes{1, 1, 1, 1, 0, 0};
    for (int32_t k = 0; k < 6; ++k) {
      auto v = UnbindT<float>(alloc, states[i * 6 + k], axes[k]);
      for (int32_t n = 0; n < batch_size; ++n) {
        ans[n].push_back(std::move(v[n]));
      }
    }
  }
  {
    auto v = UnbindT<float>(alloc, states[m * 6], 0);
    for (int32_t n = 0; n < batch_size; ++n) ans[n].push_back(std::move(v[n]));
  }
  {
    // last state is int64 (batch,) -> unbind along axis 0
    auto v = UnbindT<int64_t>(alloc, states[m * 6 + 1], 0);
    for (int32_t n = 0; n < batch_size; ++n) ans[n].push_back(std::move(v[n]));
  }
  return ans;
}

Ort::Value Zipformer2Model::BuildDecoderInput(
    const std::vector<std::vector<int64_t>> &token_seqs) {
  int32_t batch = static_cast<int32_t>(token_seqs.size());
  std::array<int64_t, 2> shape{batch, context_size_};
  Ort::Value v = Ort::Value::CreateTensor<int64_t>(allocator_, shape.data(),
                                                   shape.size());
  int64_t *p = v.GetTensorMutableData<int64_t>();
  for (const auto &t : token_seqs) {
    auto begin = t.end() - context_size_;
    std::copy(begin, t.end(), p);
    p += context_size_;
  }
  return v;
}

}  // namespace xime_asr
