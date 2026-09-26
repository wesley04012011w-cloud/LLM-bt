#include <jni.h>
#include <llama.h>

#include <cstdint>
#include <cstdio>
#include <string>

static llama_model * g_model = nullptr;
static llama_context * g_context = nullptr;
static bool g_backend_initialized = false;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_stringFromNative(JNIEnv* env, jobject) {
    return env->NewStringUTF("llama.cpp nativo conectado");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_loadModel(JNIEnv* env, jobject, jstring jpath) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("Erro: caminho do modelo inválido.");
    }

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }

    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (g_model == nullptr) {
        return env->NewStringUTF("Erro: não foi possível carregar o arquivo GGUF.");
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 2048;
    context_params.n_batch = 512;
    context_params.n_ubatch = 256;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;

    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("Erro: modelo carregou, mas o contexto nativo falhou.");
    }

    char desc[256] = {};
    llama_model_desc(g_model, desc, sizeof(desc));

    const uint64_t size_mb = llama_model_size(g_model) / (1024ULL * 1024ULL);

    std::string result = "Modelo carregado!\n";
    result += "Arquitetura: ";
    result += desc[0] ? desc : "desconhecida";
    result += "\nTamanho: ";
    result += std::to_string(size_mb);
    result += " MB\nContexto: 2048 tokens";

    return env->NewStringUTF(result.c_str());
}
