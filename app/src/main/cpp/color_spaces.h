#pragma once

#include <cmath>
#include <algorithm>

namespace decorstr {

enum class ColorSpaceMode {
    RGB = 0,
    LAB = 1,
    YUV = 2,
    DIRECT_PCA = 3,
    RAW_NORMAL = 4
};

struct Vec3 {
    float x{0.0f}, y{0.0f}, z{0.0f};

    Vec3() = default;
    Vec3(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}

    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
};

// Conversions
Vec3 linearRgbToCieLab(const Vec3& rgb);
Vec3 cieLabToLinearRgb(const Vec3& lab);

Vec3 linearRgbToYuv(const Vec3& rgb);
Vec3 yuvToLinearRgb(const Vec3& yuv);

Vec3 convertRgbToSpace(const Vec3& rgb, ColorSpaceMode mode);
Vec3 convertSpaceToRgb(const Vec3& c, ColorSpaceMode mode);

} // namespace decorstr
