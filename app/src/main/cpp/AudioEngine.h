#ifndef SONICSPHERE_AUDIOENGINE_H
#define SONICSPHERE_AUDIOENGINE_H

#include <oboe/Oboe.h>
#include <memory>
#include <vector>
#include <mutex>
#include "HaasProcessor.h"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();

    bool loadAudioBuffer(const std::vector<float>& buffer, int sampleRate, int channelCount);
    void play();
    void pause();

    void setHaasDelay(int delayMs);
    void enableHaasEffect(bool enabled);

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<HaasProcessor> haasProcessor_;

    std::vector<float> audioBuffer_;
    std::mutex mutex_;
    size_t playPosition_;
    bool isPlaying_;
    int sourceSampleRate_;
    int sourceChannels_;

    static constexpr int32_t OUTPUT_SAMPLE_RATE = 48000;
    static constexpr int32_t OUTPUT_CHANNELS = 2;
};

#endif