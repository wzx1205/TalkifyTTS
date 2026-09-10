cmake_minimum_required(VERSION 3.22.1)
project(talkify_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- 只编 llama.cpp 的推理内核，关掉测试/示例/server/curl ----
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE) # NDK 默认不带 libomp
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE) # Android 上避免 sgemm 兼容问题
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE) # 交叉编译不能探测宿主 CPU
set(LLAMA_STANDALONE       OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(talkify_llm SHARED llm_jni.cpp)
target_include_directories(talkify_llm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include)
target_link_libraries(talkify_llm PRIVATE llama ggml)
target_link_libraries(talkify_llm PRIVATE log)
