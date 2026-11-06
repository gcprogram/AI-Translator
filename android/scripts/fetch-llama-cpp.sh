#!/usr/bin/env sh
# Holt llama.cpp in der Version, gegen die die native Brücke (app/src/main/cpp) gebaut und getestet ist.
# Aufruf im Ordner android/:  sh scripts/fetch-llama-cpp.sh
set -e
TAG=b12164
DIR="$(dirname "$0")/../third_party/llama.cpp"
if [ -f "$DIR/CMakeLists.txt" ]; then
    echo "llama.cpp ist schon vorhanden: $DIR"
    exit 0
fi
mkdir -p "$(dirname "$DIR")"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$DIR"
echo "llama.cpp $TAG geladen nach $DIR"
