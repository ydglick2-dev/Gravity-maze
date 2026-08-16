cmake_minimum_required(VERSION 3.22.1)
project(kolan CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe arrives as a prefab AAR from com.google.oboe:oboe, so there is nothing to vendor.
find_package(oboe REQUIRED CONFIG)

add_library(kolan SHARED
    jni_bridge.cpp
    dsp/Fft.cpp
    dsp/Wsola.cpp
    dsp/Lpc.cpp
    dsp/FormantWarp.cpp
    dsp/Effects.cpp
    dsp/Freeverb.cpp
    dsp/PhaseVocoder.cpp
    dsp/Resampler.cpp
    dsp/VoiceChain.cpp
    engine/LiveEngine.cpp
    engine/OfflineEngine.cpp
    engine/InjectEngine.cpp
)

target_compile_options(kolan PRIVATE
    -Wall
    -Wextra
    -O3
    -ffast-math
    -fno-exceptions
    -fno-rtti
)

# Neon is baseline on arm64 and present on every armeabi-v7a device that can run API 26.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(kolan PRIVATE -mfpu=neon)
endif()

target_link_libraries(kolan
    oboe::oboe
    android
    log
)

# Drop unused code and keep only the JNI symbols visible, which trims a noticeable slice off
# the shipped .so.
target_link_options(kolan PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
set_target_properties(kolan PROPERTIES CXX_VISIBILITY_PRESET hidden)
