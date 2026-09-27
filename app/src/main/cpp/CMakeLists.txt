cmake_minimum_required(VERSION 3.22.1)
project(llmbt)

include(FetchContent)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# Android NDK cross-compilation restricts CMake package lookup to the target
# sysroot. Expose the host-installed SPIRV-Headers package to ggml-vulkan.
if(EXISTS "/usr/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
    set(SPIRV-Headers_DIR "/usr/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
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

add_library(llmbt SHARED native-lib.cpp)

target_link_libraries(llmbt PRIVATE llama)
