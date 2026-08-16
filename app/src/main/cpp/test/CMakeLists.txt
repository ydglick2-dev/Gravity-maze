cmake_minimum_required(VERSION 3.16)
project(kolan_dsp_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Threads REQUIRED)

# The dsp/ and core/ sources deliberately have no Android or Oboe dependency, so the same files
# that ship in the APK are the ones compiled here.
add_executable(kolan_dsp_tests
    dsp_tests.cpp
    ../dsp/Fft.cpp
    ../dsp/Wsola.cpp
    ../dsp/Lpc.cpp
    ../dsp/FormantWarp.cpp
    ../dsp/Effects.cpp
    ../dsp/Freeverb.cpp
    ../dsp/PhaseVocoder.cpp
    ../dsp/Resampler.cpp
    ../dsp/VoiceChain.cpp
)

target_compile_options(kolan_dsp_tests PRIVATE -O2 -Wall -Wextra)
target_link_libraries(kolan_dsp_tests PRIVATE Threads::Threads)

enable_testing()
add_test(NAME dsp COMMAND kolan_dsp_tests)
