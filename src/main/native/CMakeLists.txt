cmake_minimum_required(VERSION 3.21)
project(audio_engine C)

# Abilita Objective-C SOLO su macOS
if(APPLE)
    enable_language(OBJC)
endif()

# ── Versione C standard ───────────────────────────────────────────────────────
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ── Trova JNI ─────────────────────────────────────────────────────────────────
# JAVA_HOME deve essere impostato al JDK usato da Gradle (JDK 17).
# Gradle passa -DJAVA_HOME=... tramite il task cmake nel build.gradle.kts.
find_package(JNI REQUIRED)
message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")

# ── Sorgenti ──────────────────────────────────────────────────────────────────
set(NATIVE_SOURCES audio_engine.c)

if(APPLE)
    # Su macOS aggiungiamo il file Objective-C con ScreenCaptureKit
    list(APPEND NATIVE_SOURCES audio_engine_mac.m)
    set_source_files_properties(audio_engine_mac.m
        PROPERTIES COMPILE_FLAGS "-fobjc-arc"
    )
endif()

# ── Libreria condivisa ────────────────────────────────────────────────────────
add_library(audio_engine SHARED ${NATIVE_SOURCES})

target_include_directories(audio_engine PRIVATE ${JNI_INCLUDE_DIRS})

# ── Flag comuni ───────────────────────────────────────────────────────────────
target_compile_options(audio_engine PRIVATE
    $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:
        -Wall -Wextra -Wno-unused-parameter -O2
    >
    $<$<C_COMPILER_ID:MSVC>:
        /W3 /O2
    >
)

# ── Link per OS ───────────────────────────────────────────────────────────────
if(WIN32)
    target_link_libraries(audio_engine PRIVATE Ole32 Mmdevapi ksuser)

    set_target_properties(audio_engine PROPERTIES
        PREFIX ""
        OUTPUT_NAME "audio_engine"
    )

elseif(APPLE)
    # ScreenCaptureKit + AVFoundation + CoreAudio
    target_link_libraries(audio_engine PRIVATE
        "-framework ScreenCaptureKit"
        "-framework AVFoundation"
        "-framework CoreAudio"
        "-framework CoreMedia"
        "-framework CoreFoundation"
        "-framework Foundation"
    )
    # macOS 12.3 minimo per ScreenCaptureKit
    set_target_properties(audio_engine PROPERTIES
        MACOSX_RPATH TRUE
        BUILD_WITH_INSTALL_RPATH TRUE
        INSTALL_RPATH "@loader_path"
    )
    target_compile_options(audio_engine PRIVATE
        -mmacosx-version-min=12.3
    )

elseif(UNIX)
    # PulseAudio / PipeWire (interfaccia libpulse-simple)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(PULSEAUDIO REQUIRED libpulse-simple)
    target_include_directories(audio_engine PRIVATE ${PULSEAUDIO_INCLUDE_DIRS})
    target_link_libraries(audio_engine PRIVATE ${PULSEAUDIO_LIBRARIES})
    target_compile_options(audio_engine PRIVATE ${PULSEAUDIO_CFLAGS_OTHER})
endif()

# ── Output directory ──────────────────────────────────────────────────────────
# Il .so/.dll/.dylib viene messo in build/native/ per essere poi copiato
# da Gradle in src/main/resources/native/<os>/<arch>/
set_target_properties(audio_engine PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
)

message(STATUS "audio_engine target configured for ${CMAKE_SYSTEM_NAME}")
