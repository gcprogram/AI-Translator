// Schmale JNI-Brücke zu llama.cpp: Modell laden, einen Prompt abarbeiten, Token für Token zurückmelden, abbrechen.
// Alles Heikle (UTF-8-Grenzen zwischen Token, Stop-Wörter) steht in Kotlin (app.aitranslator.ondevice), wo es testbar ist.
#include <jni.h>
#include <android/log.h>

#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    FILE *file = nullptr;  // gehört uns; llama.cpp liest nur daraus und schließt es nicht
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    std::atomic<bool> abort{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG, TAG, "%s", text);
}

void throw_java(JNIEnv *env, const char *cls, const std::string &message) {
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, message.c_str());
}

// Rückgabecodes von generate()
constexpr jint kStopped = 0;  // Modell hat das Ende der Antwort erzeugt
constexpr jint kLength = 1;   // maxTokens oder Kontextgröße erreicht
constexpr jint kAborted = 2;  // Kotlin hat abgebrochen (abort() oder Rückgabe false der Callback-Funktion)

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_aitranslator_ondevice_NativeLlama_load(JNIEnv *env, jobject, jint fd, jint n_ctx, jint n_threads) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        backend_ready = true;
    }
    // Android erlaubt das erneute Öffnen von /proc/self/fd/N für Dateien aus dem Dateiwähler nicht ("Permission denied").
    // Deshalb den Deskriptor duplizieren und llama.cpp direkt aus dem offenen Handle lesen lassen.
    int own_fd = dup(fd);
    FILE *file = own_fd >= 0 ? fdopen(own_fd, "rb") : nullptr;
    if (file == nullptr) {
        if (own_fd >= 0) close(own_fd);
        throw_java(env, "java/lang/IllegalStateException", "Modelldatei kann nicht gelesen werden");
        return 0;
    }
    std::rewind(file);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // nur CPU
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;  // Modell wird eingeblendet statt kopiert: spart Arbeitsspeicher
    llama_model *model = llama_model_load_from_file_ptr(file, mparams);
    if (model == nullptr) {
        std::fclose(file);
        throw_java(env, "java/lang/IllegalStateException", "Modelldatei konnte nicht geladen werden (kein gültiges GGUF oder zu wenig Speicher)");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        std::fclose(file);
        throw_java(env, "java/lang/IllegalStateException", "Kontext konnte nicht angelegt werden (zu wenig Arbeitsspeicher?)");
        return 0;
    }

    auto *engine = new Engine();
    engine->file = file;
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    engine->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    LOGI("Modell geladen: n_ctx=%d, Trainingskontext=%d, Threads=%d", engine->n_ctx, llama_model_n_ctx_train(model), n_threads);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_app_aitranslator_ondevice_NativeLlama_abort(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) reinterpret_cast<Engine *>(handle)->abort.store(true);
}

JNIEXPORT void JNICALL
Java_app_aitranslator_ondevice_NativeLlama_free(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    if (engine->file != nullptr) std::fclose(engine->file);
    delete engine;
}

// Liefert die Anzahl der Prompt-Token in stats[0] (falls stats nicht null ist).
JNIEXPORT jint JNICALL
Java_app_aitranslator_ondevice_NativeLlama_generate(JNIEnv *env, jobject, jlong handle, jstring jprompt, jint max_tokens,
                                                    jfloat temperature, jobject sink, jintArray stats) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->abort.store(false);

    const char *prompt_c = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(jprompt, prompt_c);

    // Prompt in Token zerlegen. add_special: BOS wie im Chat-Template; parse_special: <start_of_turn> usw. als Steuertoken
    int n = -llama_tokenize(engine->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n <= 0) {
        throw_java(env, "java/lang/IllegalStateException", "Prompt konnte nicht zerlegt werden");
        return kStopped;
    }
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(engine->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n, true, true) < 0) {
        throw_java(env, "java/lang/IllegalStateException", "Prompt konnte nicht zerlegt werden");
        return kStopped;
    }
    if (stats != nullptr) {
        jint value = n;
        env->SetIntArrayRegion(stats, 0, 1, &value);
    }
    if (n >= engine->n_ctx) {
        throw_java(env, "java/lang/IllegalStateException",
                   "Prompt zu lang für den Kontext des Modells (" + std::to_string(n) + " von " + std::to_string(engine->n_ctx) + " Token)");
        return kStopped;
    }

    llama_memory_clear(llama_get_memory(engine->ctx), true);  // jeder Aufruf beginnt mit leerem Gedächtnis
    const auto t_start = std::chrono::steady_clock::now();

    // Prompt in Stücken verarbeiten, dazwischen auf Abbruch prüfen
    for (int i = 0; i < n; i += engine->n_batch) {
        if (engine->abort.load()) return kAborted;
        int count = std::min(engine->n_batch, n - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, count);
        if (llama_decode(engine->ctx, batch) != 0) {
            throw_java(env, "java/lang/IllegalStateException", "Prompt konnte nicht verarbeitet werden");
            return kStopped;
        }
    }

    const auto t_prompt = std::chrono::steady_clock::now();
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");

    jint result = kLength;
    int generated = 0;
    int position = n;
    std::vector<char> piece(256);
    while (generated < max_tokens && position < engine->n_ctx) {
        if (engine->abort.load()) { result = kAborted; break; }
        llama_token token = llama_sampler_sample(sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, token)) { result = kStopped; break; }

        int len = llama_token_to_piece(engine->vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (len < 0) {
            piece.resize(static_cast<size_t>(-len));
            len = llama_token_to_piece(engine->vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (len > 0) {
            jbyteArray bytes = env->NewByteArray(len);
            env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(piece.data()));
            jboolean keep_going = env->CallBooleanMethod(sink, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) { result = kAborted; break; }  // Kotlin-Ausnahme läuft nach der Rückkehr weiter
            if (!keep_going) { result = kAborted; break; }
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(engine->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            throw_java(env, "java/lang/IllegalStateException", "Erzeugen fehlgeschlagen");
            return kStopped;
        }
        generated++;
        position++;
    }
    llama_sampler_free(sampler);
    const auto t_end = std::chrono::steady_clock::now();
    const double prompt_s = std::chrono::duration<double>(t_prompt - t_start).count();
    const double gen_s = std::chrono::duration<double>(t_end - t_prompt).count();
    LOGI("Tempo: Prompt %d Token in %.1f s (%.1f/s), Antwort %d Token in %.1f s (%.2f/s)", n, prompt_s,
         prompt_s > 0 ? n / prompt_s : 0.0, generated, gen_s, gen_s > 0 ? generated / gen_s : 0.0);
    return result;
}

}  // extern "C"
