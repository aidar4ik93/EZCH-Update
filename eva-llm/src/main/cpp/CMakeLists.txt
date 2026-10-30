# EVA on-device brain: llama.cpp (tag b11500, 2026-10-08; core library only, vendored in ./llama.cpp) + JNI wrapper.
cmake_minimum_required(VERSION 3.18)
project(eva_llm C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)   # would download sources at build time (the EZCH build is offline)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # TV SoCs (Cortex-A53/A55/A73): dot-product where present is chosen at run time by ggml-cpu feature checks.
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()

add_subdirectory(llama.cpp build-llama)

add_library(eva_llm SHARED eva_llm.cpp)
target_include_directories(eva_llm PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_link_libraries(eva_llm PRIVATE llama android log)
target_link_options(eva_llm PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
