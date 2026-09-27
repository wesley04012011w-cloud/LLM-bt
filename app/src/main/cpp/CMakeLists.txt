cmake_minimum_required(VERSION 3.22.1)
project(llmbt)

include(FetchContent)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

if(EXISTS "/usr/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
    set(SPIRV-Headers_DIR "/usr/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
endif()

# llama.cpp's Vulkan C++ sources require the Khronos C++ headers.
# Android's NDK does not provide vulkan/vulkan.hpp.
if(DEFINED ENV{VULKAN_HEADERS_INCLUDE_DIR})
    set(Vulkan_INCLUDE_DIR "$ENV{VULKAN_HEADERS_INCLUDE_DIR}" CACHE PATH "" FORCE)
endif()

# Expose the host SPIR-V C++ headers to the Android cross-compiler.
if(DEFINED ENV{SPIRV_HEADERS_INCLUDE_DIR})
    include_directories("$ENV{SPIRV_HEADERS_INCLUDE_DIR}")
endif()

set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG master
    GIT_SHALLOW TRUE
)

FetchContent_MakeAvailable(llama)

# ggml-vulkan is created inside llama.cpp; attach the host SPIR-V include
# directory directly to that target for Android cross-compilation.
if(TARGET ggml-vulkan AND DEFINED ENV{SPIRV_HEADERS_INCLUDE_DIR})
    target_include_directories(ggml-vulkan PRIVATE "$ENV{SPIRV_HEADERS_INCLUDE_DIR}")
endif()

add_library(llmbt SHARED native-lib.cpp)

target_link_libraries(llmbt PRIVATE llama)
