#include "AudioEngine.h"
#include <android/log.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "AudioEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioEngine", __VA_ARGS__)

AudioEngine::AudioEngine()
        : playPosition_(0), isPlaying_(false), sourceSampleRate_(48000), sourceChannels_(2) {
    haasProcessor_ = std::make_unique<HaasProcessor>(OUTPUT_SAMPLE_RATE);
    LOGD("AudioEngine criado");
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;

    oboe::Result result = builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(OUTPUT_CHANNELS)
            ->setSampleRate(OUTPUT_SAMPLE_RATE)
            ->setDataCallback(this)
            ->openStream(stream_);

    if (result != oboe::Result::OK) {
        LOGE("Erro stream: %s", oboe::convertToText(result));
        return false;
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Erro start: %s", oboe::convertToText(result));
        return false;
    }

    LOGD("Stream OK: SR=%d", stream_->getSampleRate());
    return true;
}

void AudioEngine::stop() {
    isPlaying_ = false;
    if (stream_) {
        stream_->close();
        stream_.reset();
    }
}

bool AudioEngine::loadAudioBuffer(const std::vector<float>& buffer, int sampleRate, int channelCount) {
    std::lock_guard<std::mutex> lock(mutex_);

    audioBuffer_ = buffer;
    sourceSampleRate_ = sampleRate;
    sourceChannels_ = channelCount;
    playPosition_ = 0;

    LOGD("Buffer: %zu samples, SR=%d, CH=%d", buffer.size(), sampleRate, channelCount);
    return true;
}

void AudioEngine::play() {
    std::lock_guard<std::mutex> lock(mutex_);
    isPlaying_ = true;
    LOGD("Play");
}

void AudioEngine::pause() {
    std::lock_guard<std::mutex> lock(mutex_);
    isPlaying_ = false;
    LOGD("Pause");
}

void AudioEngine::setHaasDelay(int delayMs) {
    if (haasProcessor_) {
        haasProcessor_->setDelayTimeMs(delayMs);
    }
}

void AudioEngine::enableHaasEffect(bool enabled) {
    if (haasProcessor_) {
        haasProcessor_->setEnabled(enabled);
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {

    auto *out = static_cast<float *>(audioData);

    std::lock_guard<std::mutex> lock(mutex_);

    if (!isPlaying_ || audioBuffer_.empty()) {
        std::fill_n(out, numFrames * OUTPUT_CHANNELS, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    size_t samplesToRead = std::min(
            static_cast<size_t>(numFrames * OUTPUT_CHANNELS),
            audioBuffer_.size() - playPosition_
    );

    std::copy(
            audioBuffer_.begin() + playPosition_,
            audioBuffer_.begin() + playPosition_ + samplesToRead,
            out
    );

    std::fill_n(out + samplesToRead, numFrames * OUTPUT_CHANNELS - samplesToRead, 0.0f);

    playPosition_ += samplesToRead;

    if (playPosition_ >= audioBuffer_.size()) {
        playPosition_ = 0;
        isPlaying_ = false;
    }

    // HAAS
    if (haasProcessor_) {
        haasProcessor_->process(out, out, numFrames);
    }

    return oboe::DataCallbackResult::Continue;
}