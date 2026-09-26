#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>
#include <unistd.h>
#include <chrono>

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static bool g_backend_initialized = false;

static void stream_piece(JNIEnv *env, jobject activity, const std::string &text) {
    if (text.empty()) return;
    jclass cls = env->GetObjectClass(activity);
    if (!cls) return;
    jmethodID method = env->GetMethodID(cls, "appendGeneratedToken", "(Ljava/lang/String;)V");
    if (!method) {
        env->ExceptionClear();
        return;
    }
    jstring piece = env->NewStringUTF(text.c_str());
    if (!piece) return;
    env->CallVoidMethod(activity, method, piece);
    env->DeleteLocalRef(piece);
}

static std::string format_prompt(const std::string &text) {
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl != nullptr && tmpl[0] != '\0') {
        llama_chat_message msg{"user", text.c_str()};
        int32_t n = llama_chat_apply_template(tmpl, &msg, 1, true, nullptr, 0);
        if (n > 0) {
            std::string out(static_cast<size_t>(n) + 1, '\0');
            int32_t written = llama_chat_apply_template(
                tmpl, &msg, 1, true, out.data(), static_cast<int32_t>(out.size())
            );
            if (written > 0) {
                out.resize(static_cast<size_t>(written));
                return out;
            }
        }
    }
    return "User: " + text + "\nAssistant:";
}

static bool tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    int32_t cap = std::max<int32_t>(256, static_cast<int32_t>(text.size() * 2 + 32));
    out.resize(static_cast<size_t>(cap));
    int32_t n = llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        out.data(), cap, true, true
    );
    if (n < 0) {
        cap = -n;
        out.resize(static_cast<size_t>(cap));
        n = llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()),
            out.data(), cap, true, true
        );
    }
    if (n <= 0) {
        out.clear();
        return false;
    }
    out.resize(static_cast<size_t>(n));
    return true;
}

static std::string piece(const llama_vocab *vocab, llama_token token) {
    char buf[1024] = {};
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    return n > 0 ? std::string(buf, static_cast<size_t>(n)) : std::string();
}

static std::string result_error(const std::string &stage, int code) {
    return "ERRO [" + stage + "] llama_decode retornou " + std::to_string(code);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_stringFromNative(JNIEnv *env, jobject) {
    return env->NewStringUTF("llama.cpp nativo conectado");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_loadModel(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return env->NewStringUTF("Erro: caminho do modelo inválido.");

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    const auto load_start = std::chrono::steady_clock::now();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) return env->NewStringUTF("Erro: não foi possível carregar o arquivo GGUF.");

    const long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
    const int n_threads = static_cast<int>(std::max(2L, std::min(4L, cpu_count > 2 ? cpu_count - 2 : cpu_count)));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;

    g_context = llama_init_from_model(g_model, cp);
    if (!g_context) {
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("Erro: modelo carregou, mas o contexto nativo falhou.");
    }

    char desc[256] = {};
    llama_model_desc(g_model, desc, sizeof(desc));
    uint64_t size_mb = llama_model_size(g_model) / (1024ULL * 1024ULL);

    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - load_start).count();

    std::string result = "Modelo carregado!\nArquitetura: ";
    result += desc[0] ? desc : "desconhecida";
    result += "\nTamanho: " + std::to_string(size_mb) + " MB\nContexto: ";
    result += std::to_string(llama_n_ctx(g_context)) + " tokens";
    result += "\nThreads: " + std::to_string(n_threads);
    result += "\nTempo de carga: " + std::to_string(load_ms) + " ms";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_generateText(JNIEnv *env, jobject activity, jstring jprompt) {
    if (!g_model || !g_context) return env->NewStringUTF("ERRO [estado] nenhum modelo está carregado.");

    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    if (!p) return env->NewStringUTF("ERRO [prompt] JNI string inválida.");
    std::string user_text(p);
    env->ReleaseStringUTFChars(jprompt, p);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const std::string prompt = format_prompt(user_text);

    std::vector<llama_token> tokens;
    if (!tokenize(vocab, prompt, tokens)) {
        return env->NewStringUTF("ERRO [tokenização] nenhum token foi produzido.");
    }

    const uint32_t n_ctx = llama_n_ctx(g_context);
    constexpr int MAX_GENERATION_TOKENS = 96;
    if (tokens.size() + MAX_GENERATION_TOKENS >= n_ctx) {
        return env->NewStringUTF("ERRO [contexto] prompt grande demais.");
    }

    llama_memory_clear(llama_get_memory(g_context), true);

    // Use the same single-sequence batch helper as the current llama.cpp examples.
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));

    const auto prompt_start = std::chrono::steady_clock::now();
    const int decode_prompt = llama_decode(g_context, batch);
    const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - prompt_start).count();
    if (decode_prompt != 0) {
        return env->NewStringUTF(result_error("prompt", decode_prompt).c_str());
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    if (!sampler) return env->NewStringUTF("ERRO [sampler] não foi possível criar o sampler.");

    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    output.reserve(1024);
    int generated_tokens = 0;
    long long first_token_ms = -1;
    const auto generation_start = std::chrono::steady_clock::now();

    for (int i = 0; i < MAX_GENERATION_TOKENS; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        const std::string token_piece = piece(vocab, token);
        if (!token_piece.empty()) {
            output += token_piece;
            ++generated_tokens;
            if (first_token_ms < 0) {
                first_token_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - generation_start).count();
            }
            stream_piece(env, activity, token_piece);
        }

        llama_batch next_batch = llama_batch_get_one(
            const_cast<llama_token *>(&token), 1
        );

        const int decode_next = llama_decode(g_context, next_batch);
        if (decode_next != 0) {
            llama_sampler_free(sampler);
            return env->NewStringUTF(result_error("geração", decode_next).c_str());
        }
    }

    llama_sampler_free(sampler);

    const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - generation_start).count();
    const double tokens_per_second = generation_ms > 0 ? (generated_tokens * 1000.0 / static_cast<double>(generation_ms)) : 0.0;
    if (output.empty()) output = "(o modelo terminou sem gerar texto)";
    output += "\n\n[perf] prefill=" + std::to_string(prompt_ms) + " ms | 1o token=" + std::to_string(first_token_ms) + " ms | geracao=" + std::to_string(generation_ms) + " ms | tokens=" + std::to_string(generated_tokens) + " | tok/s=" + std::to_string(tokens_per_second);
    return env->NewStringUTF(output.c_str());
}
