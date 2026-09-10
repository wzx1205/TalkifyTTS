#!/usr/bin/env bash
# 拉取手写 JNI 依赖的 llama.cpp 源码。
#
# llama.cpp 整棵树约 87MB / 1200+ 文件，不适合直接入库，
# 因此 app/src/main/cpp/llama.cpp 被 .gitignore 排除，靠本脚本按固定 tag 还原。
#
# 用法：
#   bash scripts/fetch-llama-cpp.sh
# 之后 app/build.gradle.kts 里的 externalNativeBuild 才能编译 libtalkify_llm.so。

set -euo pipefail

LLAMA_TAG="${LLAMA_TAG:-c32d1dabe819002ca8aa3a885aca4057f5968e3d}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/cpp/llama.cpp"

if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "[fetch-llama-cpp] 已存在：$DEST（跳过）"
  exit 0
fi

echo "[fetch-llama-cpp] 拉取 ggml-org/llama.cpp @ $LLAMA_TAG -> $DEST"
# 直接按 commit checkout：master 上的老 tag（如 b4739）不认识 qwen3 架构
git init -q "$DEST"
git -C "$DEST" remote add origin https://github.com/ggml-org/llama.cpp.git
git -C "$DEST" fetch --depth 1 origin "$LLAMA_TAG"
git -C "$DEST" checkout -q FETCH_HEAD

# 去掉 .git，避免误当成 submodule；还原只依赖源码本身
rm -rf "$DEST/.git"

echo "[fetch-llama-cpp] 完成。"
