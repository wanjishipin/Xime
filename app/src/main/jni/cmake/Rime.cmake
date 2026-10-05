# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# 应用 Lua 5.4 Android 兼容性补丁（修复 32 位设备上 fseeko/ftello 不可用的问题）
# 对 liolib.c 中 l_fseek 配置块做条件增强，使 32 位 Android < API 24 能编译
# 使用 CMake 原生方式修补，无需依赖 git/patch
string(ASCII 10 LUA_NL)
set(LUA_LIOLIB_SRC "${CMAKE_SOURCE_DIR}/librime-lua-deps/lua5.4/liolib.c")
if(EXISTS "${LUA_LIOLIB_SRC}")
  file(READ "${LUA_LIOLIB_SRC}" LUA_LIOLIB_CONTENT)
  # 检查补丁是否已应用
  string(FIND "${LUA_LIOLIB_CONTENT}" "ANDROID" LUA_ALREADY_PATCHED)
  if(LUA_ALREADY_PATCHED EQUAL -1)
    string(FIND "${LUA_LIOLIB_CONTENT}" "#if !defined(l_fseek)" LUA_ANCHOR_POS)
    if(LUA_ANCHOR_POS GREATER -1)
      string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_ANCHOR_POS} -1 LUA_SUB_CONTENT)
      string(FIND "${LUA_SUB_CONTENT}" "#if defined(LUA_USE_POSIX)" LUA_REL_POS)
      if(LUA_REL_POS GREATER -1)
        math(EXPR LUA_TARGET_POS "${LUA_ANCHOR_POS} + ${LUA_REL_POS}")
        string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_TARGET_POS} -1 LUA_TARGET_CONTENT)
        string(FIND "${LUA_TARGET_CONTENT}" "${LUA_NL}" LUA_REL_NL_POS)
        if(LUA_REL_NL_POS GREATER -1)
          math(EXPR LUA_NL_POS "${LUA_TARGET_POS} + ${LUA_REL_NL_POS}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" 0 ${LUA_TARGET_POS} LUA_HEAD)
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_NL_POS} -1 LUA_TAIL)
          math(EXPR LUA_SUFFIX_START "${LUA_TARGET_POS} + 26")
          math(EXPR LUA_SUFFIX_LEN "${LUA_NL_POS} - ${LUA_SUFFIX_START}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_SUFFIX_START} ${LUA_SUFFIX_LEN} LUA_SUFFIX)
          set(LUA_PATCHED_LINE
            "#if defined(LUA_USE_POSIX) && \\${LUA_NL}   (!defined(ANDROID) || (defined(__LP64__) || ANDROID_PLATFORM >= 24))${LUA_SUFFIX}")
          set(LUA_LIOLIB_CONTENT "${LUA_HEAD}${LUA_PATCHED_LINE}${LUA_TAIL}")
          file(WRITE "${LUA_LIOLIB_SRC}" "${LUA_LIOLIB_CONTENT}")
        endif()
      endif()
    endif()
  endif()
endif()

# 已集成的插件（拷贝式同步；librime-t9 单独处理，见下）
set(RIME_PLUGINS librime-octagram librime-predict)

# 将插件复制到 plugins/ 目录。
# 顶层插件目录（librime-octagram 等）是唯一权威源码，这里在每次 configure 时
# 都全量同步，确保插件编译副本与顶层一致（file(COPY) 保留源文件时间戳，
# 内容未变的文件不会触发重编译）。
foreach(plugin ${RIME_PLUGINS})
  file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
endforeach()

# librime-t9 源码在顶层 jni/librime-t9，这里用符号链接（而非拷贝）接入
# librime 插件构建，物理上只保留一份：既避免 IDE 双份索引改错文件，也
# 从根本上消除“改副本被构建静默覆盖”的隐患。幂等：已存在则先删再建。
set(T9_PLUGIN_LINK "${CMAKE_SOURCE_DIR}/librime/plugins/librime-t9")
if(IS_SYMLINK "${T9_PLUGIN_LINK}")
  file(REMOVE "${T9_PLUGIN_LINK}")
elseif(EXISTS "${T9_PLUGIN_LINK}")
  file(REMOVE_RECURSE "${T9_PLUGIN_LINK}")
endif()
file(CREATE_LINK "../../librime-t9"
     "${T9_PLUGIN_LINK}" SYMBOLIC)

# librime-lua 需要特殊命名 lua
file(COPY "${CMAKE_SOURCE_DIR}/librime-lua/"
     DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua")

# librime-lua thirdparty 依赖（Lua 5.4 源码）
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
