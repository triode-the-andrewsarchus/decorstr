#pragma once

#include <cstdint>
#include <vector>
#include "color_spaces.h"

namespace decorstr {

struct CovarianceResult {
    float mean[3]{0.0f, 0.0f, 0.0f};
    float cov[9]{0.0f}; // 3x3 row-major
    int sampleCount{0};
    bool valid{false};
};

// Estimates color covariance and mean from a downsampled 16-bit Bayer RAW buffer
CovarianceResult estimateCovarianceBayer16(
    const uint16_t* rawData,
    int width,
    int height,
    int rowStridePixels,
    int bayerPattern, // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
    const float blackLevels[4],
    float whiteLevel,
    const float wbGains[3],
    ColorSpaceMode colorSpace,
    int targetSamplesX = 160,
    int targetSamplesY = 120
);

// Estimates covariance from 10-bit packed RAW (RAW10)
CovarianceResult estimateCovarianceBayer10(
    const uint8_t* raw10Data,
    int width,
    int height,
    int rowStrideBytes,
    int bayerPattern,
    const float blackLevels[4],
    float whiteLevel,
    const float wbGains[3],
    ColorSpaceMode colorSpace,
    int targetSamplesX = 160,
    int targetSamplesY = 120
);

} // namespace decorstr
