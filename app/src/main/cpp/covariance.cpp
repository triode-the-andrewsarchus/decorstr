#include "covariance.h"
#include <cmath>
#include <algorithm>

namespace decorstr {

static inline void extractBayerRgb(
    float p00, float p01, float p10, float p11,
    int pattern,
    float& r, float& g, float& b)
{
    switch (pattern) {
        case 0: // RGGB
            r = p00;
            g = (p01 + p10) * 0.5f;
            b = p11;
            break;
        case 1: // GRBG
            g = (p00 + p11) * 0.5f;
            r = p01;
            b = p10;
            break;
        case 2: // GBRG
            g = (p00 + p11) * 0.5f;
            b = p01;
            r = p10;
            break;
        case 3: // BGGR
        default:
            b = p00;
            g = (p01 + p10) * 0.5f;
            r = p11;
            break;
    }
}

CovarianceResult estimateCovarianceBayer16(
    const uint16_t* rawData,
    int width,
    int height,
    int rowStridePixels,
    int bayerPattern,
    const float blackLevels[4],
    float whiteLevel,
    const float wbGains[3],
    ColorSpaceMode colorSpace,
    int targetSamplesX,
    int targetSamplesY)
{
    CovarianceResult result;
    if (!rawData || width < 4 || height < 4 || rowStridePixels < width) {
        return result;
    }

    int stepX = std::max(2, (width / targetSamplesX) & ~1);
    int stepY = std::max(2, (height / targetSamplesY) & ~1);

    double sum[3] = {0.0, 0.0, 0.0};
    double sumSq[9] = {0.0}; // 3x3 outer product accumulator
    int count = 0;

    float normRange[4];
    for (int i = 0; i < 4; ++i) {
        normRange[i] = std::max(1.0f, whiteLevel - blackLevels[i]);
    }

    for (int y = 0; y + 1 < height; y += stepY) {
        const uint16_t* row0 = rawData + y * rowStridePixels;
        const uint16_t* row1 = rawData + (y + 1) * rowStridePixels;

        for (int x = 0; x + 1 < width; x += stepX) {
            float p00 = std::clamp((static_cast<float>(row0[x])     - blackLevels[0]) / normRange[0], 0.0f, 1.0f);
            float p01 = std::clamp((static_cast<float>(row0[x + 1]) - blackLevels[1]) / normRange[1], 0.0f, 1.0f);
            float p10 = std::clamp((static_cast<float>(row1[x])     - blackLevels[2]) / normRange[2], 0.0f, 1.0f);
            float p11 = std::clamp((static_cast<float>(row1[x + 1]) - blackLevels[3]) / normRange[3], 0.0f, 1.0f);

            float r, g, b;
            extractBayerRgb(p00, p01, p10, p11, bayerPattern, r, g, b);

            if (wbGains != nullptr) {
                r = std::clamp(r * wbGains[0], 0.0f, 1.0f);
                g = std::clamp(g * wbGains[1], 0.0f, 1.0f);
                b = std::clamp(b * wbGains[2], 0.0f, 1.0f);
            }

            // Outlier rejection: reject overexposed highlights and extreme black floor noise
            if ((r > 0.985f && g > 0.985f && b > 0.985f) || (r < 0.005f && g < 0.005f && b < 0.005f)) {
                continue;
            }

            Vec3 rgb(r, g, b);
            Vec3 c = convertRgbToSpace(rgb, colorSpace);

            sum[0] += c.x;
            sum[1] += c.y;
            sum[2] += c.z;

            sumSq[0] += c.x * c.x;
            sumSq[1] += c.x * c.y;
            sumSq[2] += c.x * c.z;

            sumSq[3] += c.y * c.x;
            sumSq[4] += c.y * c.y;
            sumSq[5] += c.y * c.z;

            sumSq[6] += c.z * c.x;
            sumSq[7] += c.z * c.y;
            sumSq[8] += c.z * c.z;

            count++;
        }
    }

    if (count < 64) {
        return result;
    }

    double invN = 1.0 / count;
    result.mean[0] = static_cast<float>(sum[0] * invN);
    result.mean[1] = static_cast<float>(sum[1] * invN);
    result.mean[2] = static_cast<float>(sum[2] * invN);

    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            double covVal = (sumSq[i * 3 + j] * invN) - (result.mean[i] * result.mean[j]);
            result.cov[i * 3 + j] = static_cast<float>(covVal);
        }
    }

    // Enforce symmetry
    result.cov[1] = result.cov[3] = (result.cov[1] + result.cov[3]) * 0.5f;
    result.cov[2] = result.cov[6] = (result.cov[2] + result.cov[6]) * 0.5f;
    result.cov[5] = result.cov[7] = (result.cov[5] + result.cov[7]) * 0.5f;

    result.sampleCount = count;
    result.valid = true;
    return result;
}

// Unpack RAW10 MIPI format: 4 10-bit pixels packed into 5 bytes
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
    int targetSamplesX,
    int targetSamplesY)
{
    CovarianceResult result;
    if (!raw10Data || width < 4 || height < 4) {
        return result;
    }

    // Step in groups of 4 pixels horizontally (5 bytes)
    int stepX = std::max(4, ((width / targetSamplesX) / 4) * 4);
    int stepY = std::max(2, (height / targetSamplesY) & ~1);

    double sum[3] = {0.0, 0.0, 0.0};
    double sumSq[9] = {0.0};
    int count = 0;

    float normRange[4];
    for (int i = 0; i < 4; ++i) {
        normRange[i] = std::max(1.0f, whiteLevel - blackLevels[i]);
    }

    for (int y = 0; y + 1 < height; y += stepY) {
        const uint8_t* row0 = raw10Data + y * rowStrideBytes;
        const uint8_t* row1 = raw10Data + (y + 1) * rowStrideBytes;

        for (int x = 0; x + 3 < width; x += stepX) {
            int byteOffset = (x / 4) * 5;

            // Unpack 2x2 top-left pair (pixels x and x+1)
            uint16_t p00_val = (static_cast<uint16_t>(row0[byteOffset + 0]) << 2) | ((row0[byteOffset + 4] >> 0) & 0x03);
            uint16_t p01_val = (static_cast<uint16_t>(row0[byteOffset + 1]) << 2) | ((row0[byteOffset + 4] >> 2) & 0x03);
            uint16_t p10_val = (static_cast<uint16_t>(row1[byteOffset + 0]) << 2) | ((row1[byteOffset + 4] >> 0) & 0x03);
            uint16_t p11_val = (static_cast<uint16_t>(row1[byteOffset + 1]) << 2) | ((row1[byteOffset + 4] >> 2) & 0x03);

            float p00 = std::clamp((static_cast<float>(p00_val) - blackLevels[0]) / normRange[0], 0.0f, 1.0f);
            float p01 = std::clamp((static_cast<float>(p01_val) - blackLevels[1]) / normRange[1], 0.0f, 1.0f);
            float p10 = std::clamp((static_cast<float>(p10_val) - blackLevels[2]) / normRange[2], 0.0f, 1.0f);
            float p11 = std::clamp((static_cast<float>(p11_val) - blackLevels[3]) / normRange[3], 0.0f, 1.0f);

            float r, g, b;
            extractBayerRgb(p00, p01, p10, p11, bayerPattern, r, g, b);

            if (wbGains != nullptr) {
                r = std::clamp(r * wbGains[0], 0.0f, 1.0f);
                g = std::clamp(g * wbGains[1], 0.0f, 1.0f);
                b = std::clamp(b * wbGains[2], 0.0f, 1.0f);
            }

            if ((r > 0.985f && g > 0.985f && b > 0.985f) || (r < 0.005f && g < 0.005f && b < 0.005f)) {
                continue;
            }

            Vec3 rgb(r, g, b);
            Vec3 c = convertRgbToSpace(rgb, colorSpace);

            sum[0] += c.x;
            sum[1] += c.y;
            sum[2] += c.z;

            sumSq[0] += c.x * c.x;
            sumSq[1] += c.x * c.y;
            sumSq[2] += c.x * c.z;

            sumSq[3] += c.y * c.x;
            sumSq[4] += c.y * c.y;
            sumSq[5] += c.y * c.z;

            sumSq[6] += c.z * c.x;
            sumSq[7] += c.z * c.y;
            sumSq[8] += c.z * c.z;

            count++;
        }
    }

    if (count < 64) {
        return result;
    }

    double invN = 1.0 / count;
    result.mean[0] = static_cast<float>(sum[0] * invN);
    result.mean[1] = static_cast<float>(sum[1] * invN);
    result.mean[2] = static_cast<float>(sum[2] * invN);

    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            result.cov[i * 3 + j] = static_cast<float>((sumSq[i * 3 + j] * invN) - (result.mean[i] * result.mean[j]));
        }
    }

    result.cov[1] = result.cov[3] = (result.cov[1] + result.cov[3]) * 0.5f;
    result.cov[2] = result.cov[6] = (result.cov[2] + result.cov[6]) * 0.5f;
    result.cov[5] = result.cov[7] = (result.cov[5] + result.cov[7]) * 0.5f;

    result.sampleCount = count;
    result.valid = true;
    return result;
}

} // namespace decorstr
