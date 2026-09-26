#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static bool g_backend_initialized = false;

static std::string format_prompt(const std::string &text) {
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    llama_chat_message msg{"user", text.c_str()};
    int32_t n = llama_chat_apply_template(tmpl, &msg, 1, true, nullptr, 0);
    if (n > 0) {
        std::string out(static_cast<size_t>(n) + 1, '\0');
        int32_t written = llama_chat_apply_template(tmpl, &msg, 1, true, out.data(), static_cast<int32_t>(out.size()));
        if (written > 0) {
            out.resize(static_cast<size_t>(written));
            return out;
        }
    }
    return "User: " + text + "\nAssistant:";
}

static bool tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    int32_t cap = std::max<int32_t>(256, static_cast<int32_t>(text.size() * 2 + 32));
    out.resize(static_cast<size_t>(cap));
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), cap, true, true);
    if (n < 0) {
        cap = -n;
        out.resize(static_cast<size_t>(cap));
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), cap, true, true);
    }
    if (n <= 0) { out.clear(); return false; }
    out.resize(static_cast<size_t>(n));
    return true;
}

static std::string piece(const llama_vocab *vocab, llama_token token) {
    char buf[512] = {};
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    return n > 0 ? std::string(buf, static_cast<size_t>(n)) : std::string();
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

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) return env->NewStringUTF("Erro: não foi possível carregar o arquivo GGUF.");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_ubatch = 256;
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    g_context = llama_init_from_model(g_model, cp);

    if (!g_context) {
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("Erro: modelo carregou, mas o contexto nativo falhou.");
    }

    char desc[256] = {};
    llama_model_desc(g_model, desc, sizeof(desc));
    uint64_t size_mb = llama_model_size(g_model) / (1024ULL * 1024ULL);

    std::string result = "Modelo carregado!\nArquitetura: ";
    result += desc[0] ? desc : "desconhecida";
    result += "\nTamanho: " + std::to_string(size_mb) + " MB\nContexto: ";
    result += std::to_string(llama_n_ctx(g_context)) + " tokens";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_generateText(JNIEnv *env, jobject, jstring jprompt) {
    if (!g_model || !g_context) return env->NewStringUTF("Erro: nenhum modelo está carregado.");

    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    if (!p) return env->NewStringUTF("Erro: prompt inválido.");
    std::string user_text(p);
    env->ReleaseStringUTFChars(jprompt, p);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::string prompt = format_prompt(user_text);
    std::vector<llama_token> tokens;
    if (!tokenize(vocab, prompt, tokens)) return env->NewStringUTF("Erro: não foi possível tokenizar o prompt.");

    const uint32_t n_ctx = llama_n_ctx(g_context);
    if (tokens.size() + 128 >= n_ctx) return env->NewStringUTF("Erro: mensagem grande demais para o contexto.");

    llama_memory_clear(llama_get_memory(g_context), true);

    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i + 1 == tokens.size()) ? 1 : 0;
    }

    if (llama_decode(g_context, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("Erro: falha ao processar o prompt.");
    }
    llama_batch_free(batch);

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    if (!sampler) return env->NewStringUTF("Erro: não foi possível criar o sampler.");
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    for (int i = 0; i < 128; ++i) {
        llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        output += piece(vocab, token);
        llama_sampler_accept(sampler, token);

        llama_token next = token;
        llama_batch next_batch = llama_batch_get_one(&next, 1);
        if (llama_decode(g_context, next_batch) != 0) break;
    }

    llama_sampler_free(sampler);
    if (output.empty()) output = "(o modelo não gerou texto)";
    return env->NewStringUTF(output.c_str());
}
