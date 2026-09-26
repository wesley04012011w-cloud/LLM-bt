#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>
#include <unistd.h>
#include <chrono>
#include <cstring>

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static bool g_backend_initialized = false;

struct ConversationMessage {
    std::string role;
    std::string content;
};

static std::vector<ConversationMessage> g_conversation;
static std::vector<llama_token> g_cached_tokens;
static bool g_cache_valid = false;

static constexpr const char * SYSTEM_PROMPT =
    "Você é o LLM-BT, um assistente local. "
    "Responda de forma natural, clara e direta. "
    "Prefira uma conversa humana e espontânea, evitando respostas robóticas, excessivamente formais ou desnecessariamente longas. "
    "Responda no mesmo idioma do usuário, salvo quando ele pedir outro idioma. "
    "Quando uma explicação simples for suficiente, não complique. "
    "Não invente informações quando não souber a resposta.";

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

static std::string format_prompt(const std::vector<ConversationMessage> &messages) {
    std::vector<llama_chat_message> chat;
    chat.reserve(messages.size());

    for (const auto &message : messages) {
        chat.push_back({
            message.role.c_str(),
            message.content.c_str()
        });
    }

    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl != nullptr && tmpl[0] != '\0') {
        int32_t n = llama_chat_apply_template(
            tmpl,
            chat.data(),
            chat.size(),
            true,
            nullptr,
            0
        );

        if (n > 0) {
            std::string out(static_cast<size_t>(n) + 1, '\0');
            int32_t written = llama_chat_apply_template(
                tmpl,
                chat.data(),
                chat.size(),
                true,
                out.data(),
                static_cast<int32_t>(out.size())
            );

            if (written > 0) {
                out.resize(static_cast<size_t>(written));
                return out;
            }
        }
    }

    std::string fallback;
    for (const auto &message : messages) {
        fallback += message.role;
        fallback += ": ";
        fallback += message.content;
        fallback += "\n";
    }
    fallback += "assistant: ";
    return fallback;
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

struct LlamaLogCapture {
    std::string text;
};

static void capture_llama_log(ggml_log_level, const char *text, void *user_data) {
    auto *capture = static_cast<LlamaLogCapture *>(user_data);
    if (!capture || !text) return;

    constexpr size_t MAX_LOG_SIZE = 12000;
    if (capture->text.size() >= MAX_LOG_SIZE) return;

    const size_t remaining = MAX_LOG_SIZE - capture->text.size();
    capture->text.append(text, std::min(remaining, std::strlen(text)));
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
    g_conversation.clear();
    g_cached_tokens.clear();
    g_cache_valid = false;

    const auto load_start = std::chrono::steady_clock::now();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    LlamaLogCapture log_capture;
    ggml_log_callback previous_log_callback = nullptr;
    void * previous_log_user_data = nullptr;
    llama_log_get(&previous_log_callback, &previous_log_user_data);
    llama_log_set(capture_llama_log, &log_capture);

    g_model = llama_model_load_from_file(path, mp);

    llama_log_set(previous_log_callback, previous_log_user_data);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        std::string error = "Erro: não foi possível carregar o arquivo GGUF.";
        if (!log_capture.text.empty()) {
            error += "\n\nDiagnóstico do llama.cpp:\n";
            error += log_capture.text;
        } else {
            error += "\n\nO llama.cpp não retornou detalhes pelo callback de log.";
        }
        return env->NewStringUTF(error.c_str());
    }

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

    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - load_start
    ).count();

    std::string result = "Modelo carregado!\nArquitetura: ";
    result += desc[0] ? desc : "desconhecida";
    result += "\nTamanho: " + std::to_string(size_mb) + " MB\nContexto: ";
    result += std::to_string(llama_n_ctx(g_context)) + " tokens";
    result += "\nThreads: " + std::to_string(n_threads);
    result += "\nTempo de carga: " + std::to_string(load_ms) + " ms";
    g_conversation.push_back({"system", SYSTEM_PROMPT});

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

    std::vector<ConversationMessage> candidate = g_conversation;
    candidate.push_back({"user", user_text});
    const std::string prompt = format_prompt(candidate);

    std::vector<llama_token> tokens;
    if (!tokenize(vocab, prompt, tokens)) {
        return env->NewStringUTF("ERRO [tokenização] nenhum token foi produzido.");
    }

    const uint32_t n_ctx = llama_n_ctx(g_context);
    constexpr int MAX_GENERATION_TOKENS = 192;

    if (tokens.size() + MAX_GENERATION_TOKENS >= n_ctx) {
        return env->NewStringUTF(
            "ERRO [contexto] conversa grande demais para o contexto atual. "
            "Ainda não implementei a compactação automática do histórico."
        );
    }

    bool cache_hit = false;
    size_t reused_tokens = 0;

    if (g_cache_valid &&
        tokens.size() >= g_cached_tokens.size() &&
        std::equal(g_cached_tokens.begin(), g_cached_tokens.end(), tokens.begin())) {
        cache_hit = true;
        reused_tokens = g_cached_tokens.size();
    }

    const auto prompt_start = std::chrono::steady_clock::now();

    if (!cache_hit) {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_cached_tokens.clear();
        g_cache_valid = false;
    }

    const size_t tokens_to_decode = cache_hit ? tokens.size() - reused_tokens : tokens.size();

    int decode_prompt = 0;
    if (tokens_to_decode > 0) {
        llama_token *decode_data = cache_hit
            ? const_cast<llama_token *>(tokens.data() + reused_tokens)
            : const_cast<llama_token *>(tokens.data());

        llama_batch batch = llama_batch_get_one(
            decode_data,
            static_cast<int32_t>(tokens_to_decode)
        );

        decode_prompt = llama_decode(g_context, batch);
    }

    const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - prompt_start
    ).count();

    if (decode_prompt != 0) {
        g_cached_tokens.clear();
        g_cache_valid = false;
        llama_memory_clear(llama_get_memory(g_context), true);
        return env->NewStringUTF(result_error("prompt", decode_prompt).c_str());
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    if (!sampler) {
        g_cached_tokens.clear();
        g_cache_valid = false;
        llama_memory_clear(llama_get_memory(g_context), true);
        return env->NewStringUTF("ERRO [sampler] não foi possível criar o sampler.");
    }

    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    output.reserve(2048);
    std::vector<llama_token> generated_token_ids;
    generated_token_ids.reserve(MAX_GENERATION_TOKENS);

    int generated_tokens = 0;
    long long first_token_ms = -1;
    const auto generation_start = std::chrono::steady_clock::now();

    for (int i = 0; i < MAX_GENERATION_TOKENS; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        generated_token_ids.push_back(token);

        const std::string token_piece = piece(vocab, token);
        if (!token_piece.empty()) {
            output += token_piece;
            ++generated_tokens;

            if (first_token_ms < 0) {
                first_token_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - generation_start
                ).count();
            }

            stream_piece(env, activity, token_piece);
        }

        llama_batch next_batch = llama_batch_get_one(
            const_cast<llama_token *>(&token),
            1
        );

        const int decode_next = llama_decode(g_context, next_batch);
        if (decode_next != 0) {
            llama_sampler_free(sampler);
            g_cached_tokens.clear();
            g_cache_valid = false;
            llama_memory_clear(llama_get_memory(g_context), true);
            return env->NewStringUTF(result_error("geração", decode_next).c_str());
        }
    }

    llama_sampler_free(sampler);

    const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - generation_start
    ).count();

    const double tokens_per_second =
        generation_ms > 0
            ? (generated_tokens * 1000.0 / static_cast<double>(generation_ms))
            : 0.0;

    if (output.empty()) {
        output = "(o modelo terminou sem gerar texto)";
    }

    g_cached_tokens = tokens;
    g_cached_tokens.insert(
        g_cached_tokens.end(),
        generated_token_ids.begin(),
        generated_token_ids.end()
    );
    g_cache_valid = true;

    g_conversation = std::move(candidate);
    g_conversation.push_back({"assistant", output});

    output += "\n\n[perf] cache=" + std::string(cache_hit ? "HIT" : "MISS") +
              " | reutilizados=" + std::to_string(reused_tokens) +
              " | prefill=" + std::to_string(prompt_ms) +
              " ms | 1o token=" + std::to_string(first_token_ms) +
              " ms | geracao=" + std::to_string(generation_ms) +
              " ms | tokens=" + std::to_string(generated_tokens) +
              " | tok/s=" + std::to_string(tokens_per_second);

    return env->NewStringUTF(output.c_str());
}
