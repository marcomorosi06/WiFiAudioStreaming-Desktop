# Copyright (c) 2026 Marco Morosi
#
# Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
# the European Commission - subsequent versions of the EUPL (the "Licence");
# You may not use this work except in compliance with the Licence.
# You may obtain a copy of the Licence at:
#
# https://joinup.ec.europa.eu/software/page/eupl
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the Licence is distributed on an "AS IS" basis,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the Licence for the specific language governing permissions and
# limitations under the Licence.
#
# ---------------------------------------------------------------------------
# libwfas3: the WFAS v3 core (core/, unmodified reference implementation),
# its third-party primitives (core/third_party/), libopus (opus/, a subset of
# the upstream files, unmodified) and the JNI glue (glue/).
#
# Before anything is compiled every pinned file is checked against
# MANIFEST.sha256 and core/third_party/MANIFEST.sha256; a missing, extra or
# changed file stops the build.
#
# Used by the Android build (externalNativeBuild) and by the desktop build
# (add_subdirectory from src/main/native). The caller sets:
#   WFAS3_JNI_CLASS   JNI name of the Kotlin class of the core natives
#   WFAS3_OPUS_CLASS  JNI name of the Kotlin class of the Opus natives
#   WFAS3_JNI_INCLUDE JNI include directories (not needed on Android)
#   WFAS3_OUTPUT_DIR  where the shared library goes (optional)

cmake_minimum_required(VERSION 3.18)
project(wfas3 C)

set(W3 ${CMAKE_CURRENT_SOURCE_DIR})

if(NOT DEFINED WFAS3_JNI_CLASS OR NOT DEFINED WFAS3_OPUS_CLASS)
    message(FATAL_ERROR "WFAS3_JNI_CLASS and WFAS3_OPUS_CLASS must be set")
endif()

# -- integrity of the pinned sources -----------------------------------------
include(${W3}/cmake/verify_manifest.cmake)
wfas3_verify_manifest(${W3} ${W3}/MANIFEST.sha256 "core;opus")
wfas3_verify_manifest(${W3}/core/third_party ${W3}/core/third_party/MANIFEST.sha256 ".")

# -- flags ---------------------------------------------------------------------
# Hardening for every object: stack protector, fortified libc calls, position
# independent code, zero-initialised locals, no common symbols. Each option is
# used only if a test program compiles and links with it on this toolchain
# (MinGW without libssp, for example, cannot link the stack protector).
include(CheckCSourceCompiles)
set(W3_PROBE "#include <string.h>\nint main(int c, char **v) { char b[64]; memcpy(b, v[0], (size_t)c); return b[c]; }")
function(w3_try var)
    set(CMAKE_REQUIRED_FLAGS "-Werror ${ARGN}")
    set(CMAKE_REQUIRED_LINK_OPTIONS ${ARGN})
    check_c_source_compiles("${W3_PROBE}" ${var})
endfunction()

set(W3_HARDEN -fPIC -fno-common)
set(W3_HARDEN_LINK "")
w3_try(W3_HAS_SSP -fstack-protector-strong)
if(W3_HAS_SSP)
    list(APPEND W3_HARDEN -fstack-protector-strong)
    list(APPEND W3_HARDEN_LINK -fstack-protector-strong)
else()
    message(WARNING "wfas3: stack protector not available with this toolchain")
endif()
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    list(APPEND W3_HARDEN -O2)
    w3_try(W3_HAS_FORTIFY -O2 -D_FORTIFY_SOURCE=2)
    if(W3_HAS_FORTIFY)
        list(APPEND W3_HARDEN -D_FORTIFY_SOURCE=2)
    endif()
endif()
w3_try(W3_HAS_AUTOINIT -ftrivial-auto-var-init=zero)
if(W3_HAS_AUTOINIT)
    list(APPEND W3_HARDEN -ftrivial-auto-var-init=zero)
endif()
if(NOT WIN32 AND NOT APPLE)
    w3_try(W3_HAS_CLASH -fstack-clash-protection)
    if(W3_HAS_CLASH)
        list(APPEND W3_HARDEN -fstack-clash-protection)
    endif()
endif()

# No path of the build machine in the library: the same source gives the same
# bytes in any folder (reproducible builds), and nothing about the builder leaks.
w3_try(W3_HAS_PREFIX_MAP "-ffile-prefix-map=${W3}=wfas3")
if(W3_HAS_PREFIX_MAP)
    # For every target, third-party code included: it changes no generated code.
    add_compile_options("-ffile-prefix-map=${W3}=wfas3" "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build")
endif()

set(HACL ${W3}/core/third_party/hacl)
set(TP_INC ${HACL}/include ${HACL}/karamel/include ${HACL}/karamel/krmllib/dist/minimal)

# Third-party code is compiled unmodified with its own options (core Makefile).
file(GLOB W3_HACL_SRC ${HACL}/src/*.c)
add_library(w3_hacl OBJECT ${W3_HACL_SRC})
target_include_directories(w3_hacl PRIVATE ${TP_INC})
target_compile_options(w3_hacl PRIVATE -w ${W3_HARDEN})
set_target_properties(w3_hacl PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)
target_compile_definitions(w3_hacl PRIVATE _DEFAULT_SOURCE)
# Bionic (Android) has no explicit_bzero: HACL then wipes memory through a
# volatile pointer, its portable path, chosen with the macro HACL provides.
if(ANDROID)
    target_compile_definitions(w3_hacl PRIVATE LINUX_NO_EXPLICIT_BZERO)
endif()

# The two glue files of the core (the only ones that include third-party headers).
add_library(w3_tp OBJECT ${W3}/core/wfas_tp.c)
target_include_directories(w3_tp PRIVATE ${TP_INC} ${W3}/core/third_party/fiat ${W3}/core)
target_compile_options(w3_tp PRIVATE -w ${W3_HARDEN})
set_target_properties(w3_tp PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)

add_library(w3_tp_mlkem OBJECT ${W3}/core/wfas_tp_mlkem.c)
target_include_directories(w3_tp_mlkem PRIVATE ${W3}/core/third_party/libcrux ${W3}/core)
target_compile_options(w3_tp_mlkem PRIVATE -w ${W3_HARDEN})
set_target_properties(w3_tp_mlkem PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)

# Warnings stop the build only on request: the core and the glue are checked
# with -Werror where they are developed, while the app is built by toolchains
# of many ages, whose new warnings must not break a release.
option(WFAS3_WERROR "Treat warnings of the WFAS v3 core and glue as errors" OFF)
set(W3_WERROR "")
if(WFAS3_WERROR)
    set(W3_WERROR -Werror)
endif()

# The core, with the options of its design document.
add_library(w3_core OBJECT ${W3}/core/wfas.c)
target_include_directories(w3_core PRIVATE ${W3}/core)
target_compile_options(w3_core PRIVATE -Wall -Wextra ${W3_WERROR} ${W3_HARDEN})
set_target_properties(w3_core PROPERTIES C_STANDARD 99 C_EXTENSIONS OFF)

# libopus: float build, no run-time CPU detection, no intrinsics, no DNN.
file(GLOB W3_OPUS_CELT ${W3}/opus/celt/*.c)
file(GLOB W3_OPUS_SILK ${W3}/opus/silk/*.c)
file(GLOB W3_OPUS_SILK_FLOAT ${W3}/opus/silk/float/*.c)
set(W3_OPUS_SRC
    ${W3}/opus/src/opus.c
    ${W3}/opus/src/opus_decoder.c
    ${W3}/opus/src/opus_encoder.c
    ${W3}/opus/src/extensions.c
    ${W3}/opus/src/repacketizer.c
    ${W3}/opus/src/analysis.c
    ${W3}/opus/src/mlp.c
    ${W3}/opus/src/mlp_data.c)
add_library(w3_opus OBJECT ${W3_OPUS_CELT} ${W3_OPUS_SILK} ${W3_OPUS_SILK_FLOAT} ${W3_OPUS_SRC})
target_include_directories(w3_opus PRIVATE ${W3}/opus/include ${W3}/opus/celt ${W3}/opus/silk
    ${W3}/opus/silk/float ${W3}/opus/src ${W3}/opus)
target_compile_definitions(w3_opus PRIVATE OPUS_BUILD USE_ALLOCA HAVE_LRINT=1 HAVE_LRINTF=1
    DISABLE_DEBUG_FLOAT=1)
target_compile_options(w3_opus PRIVATE -w -fvisibility=hidden ${W3_HARDEN})
set_target_properties(w3_opus PROPERTIES C_STANDARD 99 C_EXTENSIONS ON)

# The library: JNI glue + everything above. Only JNI_OnLoad is exported.
add_library(wfas3 SHARED ${W3}/glue/wfas3_jni.c ${W3}/glue/wfas3_opus.c
    $<TARGET_OBJECTS:w3_hacl> $<TARGET_OBJECTS:w3_tp> $<TARGET_OBJECTS:w3_tp_mlkem>
    $<TARGET_OBJECTS:w3_core> $<TARGET_OBJECTS:w3_opus>)
target_include_directories(wfas3 PRIVATE ${W3}/core ${W3}/opus/include ${W3}/glue ${TP_INC})
if(DEFINED WFAS3_JNI_INCLUDE)
    target_include_directories(wfas3 PRIVATE ${WFAS3_JNI_INCLUDE})
endif()
target_compile_definitions(wfas3 PRIVATE
    WFAS3_JNI_CLASS="${WFAS3_JNI_CLASS}" WFAS3_OPUS_CLASS="${WFAS3_OPUS_CLASS}")
target_compile_options(wfas3 PRIVATE -Wall -Wextra ${W3_WERROR} -Wno-unused-parameter
    -fvisibility=hidden ${W3_HARDEN})
set_target_properties(wfas3 PROPERTIES C_STANDARD 11 C_EXTENSIONS ON C_VISIBILITY_PRESET hidden)
target_link_options(wfas3 PRIVATE ${W3_HARDEN_LINK})

if(WIN32)
    target_link_libraries(wfas3 PRIVATE bcrypt)
    set_target_properties(wfas3 PROPERTIES PREFIX "")
    # -static, not only -static-libgcc: with -fstack-protector-strong many
    # MinGW toolchains link libssp-0.dll, which a user's Windows does not have,
    # and the library would not load. Statically linked, wfas3.dll needs only
    # system DLLs (kernel32, bcrypt, advapi32, msvcrt).
    target_link_options(wfas3 PRIVATE -Wl,--nxcompat -Wl,--dynamicbase -Wl,--high-entropy-va
        -static)
elseif(APPLE)
    target_link_options(wfas3 PRIVATE -Wl,-dead_strip
        -Wl,-exported_symbols_list,${W3}/glue/exports.apple)
else()
    target_link_libraries(wfas3 PRIVATE m)
    target_link_options(wfas3 PRIVATE -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack
        -Wl,--no-undefined -Wl,--gc-sections -Wl,--version-script=${W3}/glue/exports.map)
    if(ANDROID)
        # 16 KB pages (Android 15+ devices).
        target_link_options(wfas3 PRIVATE -Wl,-z,max-page-size=16384)
    endif()
endif()

if(DEFINED WFAS3_OUTPUT_DIR)
    set_target_properties(wfas3 PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${WFAS3_OUTPUT_DIR}
        RUNTIME_OUTPUT_DIRECTORY ${WFAS3_OUTPUT_DIR})
endif()
