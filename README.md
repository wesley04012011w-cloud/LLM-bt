# LLM BT

Primeira versão do projeto de chat com LLM local para Android.

## Objetivo da V0.1

- Android APK
- interface simples, fundo branco
- campo de mensagem + botão Enviar
- base nativa em C++
- integração inicial com llama.cpp via CMake
- GitHub Actions para gerar o APK

## Modelo

O modelo GGUF não fica dentro do Git. Ele será adicionado em uma próxima etapa e carregado pelo aplicativo a partir do armazenamento privado do Android.

## Build

O workflow `.github/workflows/build-apk.yml` gera um APK debug.

A integração atual baixa o código do llama.cpp durante a compilação nativa. O Android usa `arm64-v8a`, seguindo a configuração documentada pelo projeto llama.cpp.
