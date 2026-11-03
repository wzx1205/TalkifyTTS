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
# mtmd（多模态库）：COMMON/TOOLS 关闭时默认不编，但 Qwen3-TTS 的音频管线
# （说话人编码 + codec + 声码器）在 libmtmd 里，必须显式打开
set(LLAMA_BUILD_MTMD       ON  CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE) # NDK 默认不带 libomp
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE) # Android 上避免 sgemm 兼容问题
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE) # 交叉编译不能探测宿主 CPU
set(LLAMA_STANDALONE       OFF CACHE BOOL "" FORCE)

# ---- Adreno GPU (OpenCL) 加速后端，仅 arm64 ----
# 头文件：Khronos OpenCL-Headers 本地副本（opencl-headers/CL）；
# 链接桩：从真机 /vendor/lib64 拉取的 libOpenCL.so（opencl-lib/，仅链接期用，
# 不打包进 APK——AGP 只收 src/main/jniLibs，运行时由系统命名空间解析系统库）。
# 内核嵌入需要 Python（WindowsApps 的 python 别名不可用，显式指向真实安装）。
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENCL            ON  CACHE BOOL "" FORCE)
    set(OpenCL_INCLUDE_DIR     "${CMAKE_CURRENT_SOURCE_DIR}/opencl-headers" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY         "${CMAKE_CURRENT_SOURCE_DIR}/opencl-lib/arm64/libOpenCL.so" CACHE FILEPATH "" FORCE)
    set(Python3_EXECUTABLE     "C:/Users/wzx12/AppData/Local/Programs/Python/Python312/python.exe" CACHE FILEPATH "" FORCE)
endif()

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(talkify_llm SHARED
        llm_jni.cpp
        tts_jni.cpp)

# ---- QNN HTP NPU 探针(仅本机构建且装有 QAIRT SDK 时编译)----
# SDK 头为 Qualcomm 专有,不入库;探针仅 dlopen 运行时,不新增链接依赖。
set(QAIRT_SDK_ROOT "D:/Zcode/QAIRT/qairt/2.51.0.260929" CACHE PATH "QAIRT SDK root")
if (EXISTS "${QAIRT_SDK_ROOT}/include/QNN/QnnInterface.h")
    message(STATUS "qnn_probe: QAIRT SDK found, enabling HTP probe")
    target_sources(talkify_llm PRIVATE qnn_probe.cpp qnn_probe2.cpp qnn_probe3.cpp qnn_vocoder.cpp)
    target_compile_definitions(talkify_llm PRIVATE HAS_QAIRT_SDK=1)
    target_include_directories(talkify_llm PRIVATE
            "${QAIRT_SDK_ROOT}/include/QNN"
            "${QAIRT_SDK_ROOT}/include/QAIRT"
            "${QAIRT_SDK_ROOT}/share/QNN/converter/jni")
else()
    # 无 SDK 时给引擎一个空实现,NPU 声码器静默退化为 ggml,API 不变
    target_sources(talkify_llm PRIVATE qnn_vocoder_stub.cpp)
endif()
target_include_directories(talkify_llm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd)
target_link_libraries(talkify_llm PRIVATE llama ggml mtmd)
target_link_libraries(talkify_llm PRIVATE log)
