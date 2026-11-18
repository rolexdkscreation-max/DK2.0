# 18 नवंबर 2026 (मालिक: "सुनना अलग, सोचना-बोलना अलग - फ़ोन पर ऑफलाइन Whisper"):
# whisper.cpp (MIT, github.com/ggml-org/whisper.cpp) का पक्का संस्करण v1.9.5 - build के समय उसी tag का tarball
# (SHA-256 मिलाकर) उतरता है, फिर एक छोटी JNI लाइब्रेरी `libdk_whisper.so` (सिर्फ़ arm64-v8a) बनती है.
# मॉडल (ggml-small-q5_1.bin) APK में नहीं - ऐप के डाउनलोड वाले हिस्से से (ModelCatalog.WHISPER_SMALL).
cmake_minimum_required(VERSION 3.22)
project(dk_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# debug build में भी तेज़ (वरना Whisper कई गुना धीमा) - O3 हमेशा
add_compile_options(-O3 -ffunction-sections -fdata-sections)

# whisper.cpp / ggml की सेटिंग: सिर्फ़ लाइब्रेरी, कोई example / test / server नहीं; OpenMP नहीं (अपना thread pool)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# armv8.2 + dotprod + fp16 = q5_1 मॉडल पर कई गुना तेज़. पुराने CPU (सिर्फ़ armv8.0) पर यह .so लोड ही नहीं किया जाता -
# WhisperNative.cpuOk() /proc/cpuinfo देखकर पहले जाँचता है (crash नहीं, Android की पहचान पर चलता है).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
)
FetchContent_MakeAvailable(whisper)

add_library(dk_whisper SHARED dk_whisper_jni.cpp)
find_library(LOG_LIB log)
target_link_libraries(dk_whisper PRIVATE whisper ${LOG_LIB} android)
target_compile_options(dk_whisper PRIVATE -fvisibility=hidden)
target_link_options(dk_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
