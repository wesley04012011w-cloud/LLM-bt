#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_llmbt_MainActivity_stringFromNative(JNIEnv* env, jobject) {
    return env->NewStringUTF("llama.cpp nativo conectado");
}
