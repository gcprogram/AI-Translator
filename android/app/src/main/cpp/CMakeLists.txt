cmake_minimum_required(VERSION 3.22.1)
project(aitranslator_llama LANGUAGES C CXX)

# llama.cpp liegt in einer festen Version unter android/third_party (nicht im Repo, siehe scripts/fetch-llama-cpp.sh)
set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp fehlt. Im Ordner android/ ausführen: scripts/fetch-llama-cpp.sh")
endif()

# Immer optimiert bauen, auch im Debug-Build der App: ein ungeoptimiertes ggml ist um ein Vielfaches langsamer
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)    # Cross-Build: nicht auf die Build-Maschine optimieren
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
# ARMv8.2 mit Skalarprodukt-Befehlen: ab etwa 2018 in praktisch allen Handys, deutlich schnellere Quantisierung
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama.cpp-build)

add_library(aitranslator_llama SHARED llama_jni.cpp)
target_include_directories(aitranslator_llama PRIVATE "${LLAMA_DIR}/include" "${LLAMA_DIR}/ggml/include")
target_link_libraries(aitranslator_llama PRIVATE llama ggml log android)
