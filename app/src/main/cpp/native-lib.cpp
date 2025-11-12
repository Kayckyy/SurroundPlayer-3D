#include <jni.h>
#include <memory>
#include "AudioEngine.h"
#include <android/log.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "NativeLib", __VA_ARGS__)

static std::unique_ptr<AudioEngine> audioEngine;

extern "C" {

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeCreate(JNIEnv *env, jobject thiz) {
    audioEngine = std::make_unique<AudioEngine>();
    LOGD("AudioEngine criado");
}

JNIEXPORT jboolean JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeStart(JNIEnv *env, jobject thiz) {
    return audioEngine ? audioEngine->start() : false;
}

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeStop(JNIEnv *env, jobject thiz) {
    if (audioEngine) audioEngine->stop();
}

JNIEXPORT jboolean JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeLoadAudio(
        JNIEnv *env, jobject thiz,
        jfloatArray pcmData, jint sampleRate, jint channelCount) {

    if (!audioEngine) return false;

    jsize length = env->GetArrayLength(pcmData);
    jfloat* data = env->GetFloatArrayElements(pcmData, nullptr);

    std::vector<float> buffer(data, data + length);
    bool result = audioEngine->loadAudioBuffer(buffer, sampleRate, channelCount);

    env->ReleaseFloatArrayElements(pcmData, data, 0);

    LOGD("Buffer carregado: %d samples", length);
    return result;
}

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativePlay(JNIEnv *env, jobject thiz) {
    if (audioEngine) audioEngine->play();
}

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativePause(JNIEnv *env, jobject thiz) {
    if (audioEngine) audioEngine->pause();
}

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeSetHaasDelay(
        JNIEnv *env, jobject thiz, jint delayMs) {
    if (audioEngine) audioEngine->setHaasDelay(delayMs);
}

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeEnableHaas(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    if (audioEngine) audioEngine->enableHaasEffect(enabled);
}

JNIEXPORT void JNICALL
Java_com_sonicsphere_audio_service_OboeAudioEngine_nativeDestroy(JNIEnv *env, jobject thiz) {
    audioEngine.reset();
    LOGD("AudioEngine destruído");
}

}