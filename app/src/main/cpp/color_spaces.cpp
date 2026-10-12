#include "color_spaces.h"

namespace decorstr {

static inline float labF(float t) {
    constexpr float delta = 6.0f / 29.0f;
    constexpr float delta3 = delta * delta * delta;
    if (t > delta3) {
        return std::cbrt(t);
    } else {
        return t / (3.0f * delta * delta) + 4.0f / 29.0f;
    }
}

static inline float labInvF(float t) {
    constexpr float delta = 6.0f / 29.0f;
    if (t > delta) {
        return t * t * t;
    } else {
        return 3.0f * delta * delta * (t - 4.0f / 29.0f);
    }
}

Vec3 linearRgbToCieLab(const Vec3& rgb) {
    // D65 reference white
    constexpr float Xn = 0.95047f;
    constexpr float Yn = 1.00000f;
    constexpr float Zn = 1.08883f;

    // Linear RGB to CIE XYZ (sRGB D65 primaries)
    float X = 0.4124564f * rgb.x + 0.3575761f * rgb.y + 0.1804375f * rgb.z;
    float Y = 0.2126729f * rgb.x + 0.7151522f * rgb.y + 0.0721750f * rgb.z;
    float Z = 0.0193339f * rgb.x + 0.1191920f * rgb.y + 0.9503041f * rgb.z;

    float fx = labF(X / Xn);
    float fy = labF(Y / Yn);
    float fz = labF(Z / Zn);

    float L = 116.0f * fy - 16.0f;
    float a = 500.0f * (fx - fy);
    float b = 200.0f * (fy - fz);

    // Normalize roughly to [0, 1] range for covariance stability:
    // L in [0, 100] -> [0, 1]
    // a in [-100, 100] -> offset and scale
    return {L * 0.01f, (a + 128.0f) / 255.0f, (b + 128.0f) / 255.0f};
}

Vec3 cieLabToLinearRgb(const Vec3& lab) {
    constexpr float Xn = 0.95047f;
    constexpr float Yn = 1.00000f;
    constexpr float Zn = 1.08883f;

    // Denormalize
    float L = lab.x * 100.0f;
    float a = lab.y * 255.0f - 128.0f;
    float b = lab.z * 255.0f - 128.0f;

    float fy = (L + 16.0f) / 116.0f;
    float fx = a / 500.0f + fy;
    float fz = fy - b / 200.0f;

    float X = Xn * labInvF(fx);
    float Y = Yn * labInvF(fy);
    float Z = Zn * labInvF(fz);

    // CIE XYZ to Linear sRGB
    float r =  3.2404542f * X - 1.5371385f * Y - 0.4985314f * Z;
    float g = -0.9692660f * X + 1.8760108f * Y + 0.0415560f * Z;
    float bl =  0.0556434f * X - 0.2040259f * Y + 1.0572252f * Z;

    return {r, g, bl};
}

Vec3 linearRgbToYuv(const Vec3& rgb) {
    float y = 0.299f * rgb.x + 0.587f * rgb.y + 0.114f * rgb.z;
    float u = -0.14713f * rgb.x - 0.28886f * rgb.y + 0.436f * rgb.z;
    float v = 0.615f * rgb.x - 0.51499f * rgb.y - 0.10001f * rgb.z;
    return {y, u + 0.5f, v + 0.5f};
}

Vec3 yuvToLinearRgb(const Vec3& yuv) {
    float y = yuv.x;
    float u = yuv.y - 0.5f;
    float v = yuv.z - 0.5f;
    float r = y + 1.13983f * v;
    float g = y - 0.39465f * u - 0.58060f * v;
    float b = y + 2.03211f * u;
    return {r, g, b};
}

Vec3 convertRgbToSpace(const Vec3& rgb, ColorSpaceMode mode) {
    switch (mode) {
        case ColorSpaceMode::LAB:
            return linearRgbToCieLab(rgb);
        case ColorSpaceMode::YUV:
            return linearRgbToYuv(rgb);
        case ColorSpaceMode::RGB:
        case ColorSpaceMode::DIRECT_PCA:
        case ColorSpaceMode::RAW_NORMAL:
        default:
            return rgb;
    }
}

Vec3 convertSpaceToRgb(const Vec3& c, ColorSpaceMode mode) {
    switch (mode) {
        case ColorSpaceMode::LAB:
            return cieLabToLinearRgb(c);
        case ColorSpaceMode::YUV:
            return yuvToLinearRgb(c);
        case ColorSpaceMode::RGB:
        case ColorSpaceMode::DIRECT_PCA:
        case ColorSpaceMode::RAW_NORMAL:
        default:
            return c;
    }
}

} // namespace decorstr
