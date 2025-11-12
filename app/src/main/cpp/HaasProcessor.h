#ifndef SONICSPHERE_HAAPROCESSOR_H
#define SONICSPHERE_HAAPROCESSOR_H

#include <vector>
#include <cmath>

class HaasProcessor {
public:
    HaasProcessor(int sampleRate);
    ~HaasProcessor();

    void setEnabled(bool enabled);
    void setDelayTimeMs(int delayMs);

    void process(float* inputBuffer, float* outputBuffer, int numFrames);

private:
    bool enabled_;
    int sampleRate_;
    int delayTimeMs_;
    int delaySamples_;

    std::vector<float> leftDelayBuffer_;
    std::vector<float> rightDelayBuffer_;
    int delayBufferIndex_;

    static constexpr int MAX_DELAY_MS = 500;

    void updateDelayBuffers();
};

#endif // SONICSPHERE_HAAPROCESSOR_H