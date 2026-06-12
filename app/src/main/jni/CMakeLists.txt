cmake_minimum_required(VERSION 3.18.0)

project(kime VERSION 1.0.0)

set(CMAKE_CXX_STANDARD 17)

# Debug 构建也启用优化，解决大词典查询性能问题
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -g")
endif()

option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

if(ANDROID)
  set(CMAKE_THREAD_LIBS_INIT "" CACHE STRING "" FORCE)
endif()

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
add_subdirectory(librime/deps/glog)
target_compile_options(glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(Glog_FOUND TRUE CACHE BOOL "" FORCE)
set(Glog_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/glog/src" CACHE PATH "" FORCE)
set(Glog_LIBRARY glog CACHE STRING "" FORCE)
set(Glog_STATIC TRUE CACHE BOOL "" FORCE)

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

set(YamlCpp_FOUND TRUE CACHE BOOL "" FORCE)
set(YamlCpp_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/yaml-cpp/include" CACHE PATH "" FORCE)
set(YamlCpp_LIBRARY yaml-cpp CACHE STRING "" FORCE)
set(YamlCpp_STATIC TRUE CACHE BOOL "" FORCE)
set(YamlCpp_NEW_API TRUE CACHE BOOL "" FORCE)

# 配置 snappy (用于 leveldb 压缩)
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

set(LevelDb_FOUND TRUE CACHE BOOL "" FORCE)
set(LevelDb_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/leveldb/include" CACHE PATH "" FORCE)
set(LevelDb_LIBRARY leveldb CACHE STRING "" FORCE)
set(LevelDb_STATIC TRUE CACHE BOOL "" FORCE)

set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(librime/deps/marisa-trie)
target_compile_options(marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(Marisa_FOUND TRUE CACHE BOOL "" FORCE)
set(Marisa_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/marisa-trie/include" CACHE PATH "" FORCE)
set(Marisa_LIBRARY marisa CACHE STRING "" FORCE)
set(Marisa_STATIC TRUE CACHE BOOL "" FORCE)
set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)

add_subdirectory(librime/deps/opencc)
include(OpenccWorkarounds)

set(Opencc_FOUND TRUE CACHE BOOL "" FORCE)
set(Opencc_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/opencc/src" CACHE PATH "" FORCE)
set(Opencc_LIBRARY libopencc CACHE STRING "" FORCE)
set(Opencc_STATIC TRUE CACHE BOOL "" FORCE)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include_directories(
    "${CMAKE_BINARY_DIR}/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/algorithm/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/crc/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/dll/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/interprocess/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/range/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/regex/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/scope_exit/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/signals2/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/utility/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/uuid/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/assert/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/config/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/core/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/throw_exception/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/static_assert/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/mpl/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/preprocessor/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/type_traits/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/smart_ptr/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/move/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/container/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/container_hash/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/intrusive/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/detail/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/integer/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/predef/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/io/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/lexical_cast/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/numeric/conversion/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/spirit/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/serialization/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/foreach/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/iterator/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/function/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/bind/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/tuple/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/optional/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/any/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/array/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/mp11/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/unordered/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/multi_index/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/variant/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/concept_check/include"
)

include(Rime)

# 编译 JNI 库
add_subdirectory(librime_jni)

# ONNX Runtime JNI 库
set(ONNXRUNTIME_DIR "${CMAKE_SOURCE_DIR}/onnxruntime")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNXRUNTIME_DIR}/lib/${ANDROID_ABI}/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${ONNXRUNTIME_DIR}/include"
)

add_library(onnx_jni SHARED
    onnx_jni.cc
)

target_include_directories(onnx_jni PRIVATE
    ${ONNXRUNTIME_DIR}/include
)

target_link_libraries(onnx_jni
    onnxruntime
    log
)

target_link_options(onnx_jni PRIVATE "-Wl,-z,max-page-size=16384")

# Punctuation ONNX JNI 库
add_library(punctuation_jni SHARED
    punctuation_jni.cc
)

target_include_directories(punctuation_jni PRIVATE
    ${ONNXRUNTIME_DIR}/include
)

target_link_libraries(punctuation_jni
    onnxruntime
    log
)

target_link_options(punctuation_jni PRIVATE "-Wl,-z,max-page-size=16384")

# sherpa-onnx ASR JNI 库
set(SHERPA_ONNX_SRC_DIR "${CMAKE_SOURCE_DIR}/sherpa-onnx-src")
set(SHERPA_ONNXRUNTIME_LIB_DIR "${ONNXRUNTIME_DIR}/lib/${ANDROID_ABI}")
set(SHERPA_ONNXRUNTIME_INCLUDE_DIR "${ONNXRUNTIME_DIR}/include")

option(SHERPA_ONNX_ENABLE_TTS "" OFF)
option(SHERPA_ONNX_ENABLE_OFFLINE_TTS "" OFF)
option(SHERPA_ONNX_ENABLE_PYTHON "" OFF)
option(SHERPA_ONNX_ENABLE_TESTS "" OFF)
option(SHERPA_ONNX_ENABLE_C_API "" OFF)
option(SHERPA_ONNX_ENABLE_WEBSOCKET "" OFF)
target_link_options(onnx_jni PRIVATE "-Wl,-z,max-page-size=16384")

# sherpa-onnx JNI 库（预编译，使用项目的 onnxruntime）
# libsherpa-onnx-jni.so 需要通过 build-sherpa-onnx-for-xime.sh 构建
set(SHERPA_ONNX_JNI_DIR "${CMAKE_SOURCE_DIR}/sherpa_onnx_jni")

if(EXISTS "${SHERPA_ONNX_JNI_DIR}/lib/${ANDROID_ABI}/libsherpa-onnx-jni.so")
  add_library(sherpa-onnx-jni SHARED IMPORTED)
  set_target_properties(sherpa-onnx-jni PROPERTIES
      IMPORTED_LOCATION "${SHERPA_ONNX_JNI_DIR}/lib/${ANDROID_ABI}/libsherpa-onnx-jni.so"
  )
endif()