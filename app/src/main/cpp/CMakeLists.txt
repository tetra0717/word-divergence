cmake_minimum_required(VERSION 3.22.1)
project(word_divergence_native LANGUAGES C CXX)

include(FetchContent)

set(USEARCH_BUILD_TEST_CPP OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_BENCH_CPP OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_LIB_C OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_TEST_C OFF CACHE BOOL "" FORCE)
set(USEARCH_BUILD_JNI OFF CACHE BOOL "" FORCE)
set(USEARCH_USE_OPENMP OFF CACHE BOOL "" FORCE)
set(USEARCH_USE_JEMALLOC OFF CACHE BOOL "" FORCE)
set(USEARCH_USE_NUMKONG OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    usearch
    GIT_REPOSITORY https://github.com/unum-cloud/USearch.git
    GIT_TAG v2.26.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(usearch)

# Upstream java/CMakeLists.txt uses desktop FindJNI, which is not suitable when
# Android's NDK toolchain is cross-compiling. The NDK already exposes jni.h
# through its sysroot, so compile the official JNI source directly.
add_library(
    usearch_jni
    SHARED
    ${usearch_SOURCE_DIR}/java/cloud/unum/usearch/cloud_unum_usearch_Index.cpp
)
target_link_libraries(usearch_jni PRIVATE usearch)
target_compile_features(usearch_jni PRIVATE cxx_std_17)
