# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# if you want to add some new plugins, add them to librime_jni/rime_jni.cc too
set(RIME_PLUGINS librime-lua librime-octagram librime-predict)

# symlink plugins
foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}/CMakeLists.txt")
    # Creating directory symlinks requires elevated rights on Windows. CMake's
    # COPY_ON_ERROR can leave an empty directory there, so copy explicitly.
    file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
         DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
  endif()
endforeach()

# librime-lua
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty/lua5.4/lua.h")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)

# Keep the multi-million-entry Ice dictionary deployment practical even in the
# installable debug APK. Debug symbols remain; per-entry DLOG/formatting and
# unoptimized dictionary loops do not belong in this path.
foreach(dict_source dict/table dict/dict_compiler dict/entry_collector
                    dict/vocabulary dict/prism dict/string_table dict/dictionary
                    dict/preset_vocabulary dict/mapped_file algo/algebra)
  set_property(
    SOURCE "${CMAKE_SOURCE_DIR}/librime/src/rime/${dict_source}.cc"
    TARGET_DIRECTORY rime-static
    APPEND PROPERTY COMPILE_OPTIONS "$<$<CONFIG:Debug>:-O2>")
  set_property(
    SOURCE "${CMAKE_SOURCE_DIR}/librime/src/rime/${dict_source}.cc"
    TARGET_DIRECTORY rime-static
    APPEND PROPERTY COMPILE_DEFINITIONS "$<$<CONFIG:Debug>:NDEBUG>")
endforeach()
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

target_compile_options(
  rime-octagram-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
