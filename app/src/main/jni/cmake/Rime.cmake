# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# 已集成的插件（需先在 plugins/ 下创建对应目录或配置好复制逻辑）
# 注意：librime-lua 官方文档要求插件目录名为 lua
set(RIME_PLUGINS librime-lua)

# 将插件复制到 plugins/ 目录（Windows 不支持符号链接，故用复制）
foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua")
    file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
         DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua")
  endif()
endforeach()

# librime-lua thirdparty 依赖（Lua 5.4 源码）
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
