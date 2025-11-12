#include "HaasProcessor.h"
#include <android/log.h>

#define LOG_TAG "HaasProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

HaasProcessor::HaasProcessor(int sampleRate)
        : enabled_(false)
        , sampleRate_(sampleRate)
        , delayTimeMs_(0)
        , delaySamples_(0)
        , delayBufferIndex_(0) {

    updateDelayBuffers();
    LOGD("HaasProcessor criado com sample rate: %d", sampleRate);
}

HaasProcessor::~HaasProcessor() {
    LOGD("HaasProcessor destruído");
}

void HaasProcessor::setEnabled(bool enabled) {
    enabled_ = enabled;
    LOGD("Haas Effect %s", enabled ? "ativado" : "desativado");
}

void HaasProcessor::setDelayTimeMs(int delayMs) {
    if (delayMs < 0 || delayMs > MAX_DELAY_MS) {
        LOGD("Delay inválido: %d ms", delayMs);
        return;
    }

    delayTimeMs_ = delayMs;
    delaySamples_ = static_cast<int>((delayMs / 1000.0f) * sampleRate_);
    updateDelayBuffers();

    LOGD("Delay configurado: %d ms (%d samples)", delayMs, delaySamples_);
}

void HaasProcessor::updateDelayBuffers() {
    int maxDelaySamples = static_cast<int>((MAX_DELAY_MS / 1000.0f) * sampleRate_);
    leftDelayBuffer_.resize(maxDelaySamples, 0.0f);
    rightDelayBuffer_.resize(maxDelaySamples, 0.0f);
    delayBufferIndex_ = 0;
}

void HaasProcessor::process(float* inputBuffer, float* outputBuffer, int numFrames) {
    if (!enabled_ || delaySamples_ == 0) {
        // Se desabilitado, apenas copia input para output
        for (int i = 0; i < numFrames * 2; ++i) {
            outputBuffer[i] = inputBuffer[i];
        }
        return;
    }

    // Processar cada frame (estéreo intercalado: L, R, L, R, ...)
    for (int i = 0; i < numFrames; ++i) {
        int inputIndex = i * 2;

        // Ler samples L/R do input
        float leftSample = inputBuffer[inputIndex];
        float rightSample = inputBuffer[inputIndex + 1];

        // Ler samples atrasados do buffer circular
        float delayedLeft = leftDelayBuffer_[delayBufferIndex_];
        float delayedRight = rightDelayBuffer_[delayBufferIndex_];

        // Escrever novos samples no buffer de delay
        leftDelayBuffer_[delayBufferIndex_] = leftSample;
        rightDelayBuffer_[delayBufferIndex_] = rightSample;

        // Avançar índice circular
        delayBufferIndex_ = (delayBufferIndex_ + 1) % delaySamples_;

        // EFEITO HAAS: Canal esquerdo original, canal direito com delay
        outputBuffer[inputIndex] = leftSample;
        outputBuffer[inputIndex + 1] = delayedRight;
    }
}