#version 300 es
precision highp float;
precision highp int;

in vec2 v_TexCoord;
out vec4 fragColor;

uniform usampler2D u_RawTexture;
uniform vec2 u_TextureSize;
uniform int u_CfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
uniform vec4 u_BlackLevel;
uniform float u_WhiteLevel;
uniform mat3 u_DecorrelationMatrix;
uniform vec3 u_DecorrelationBias;
uniform vec3 u_WbGains; // Red, Green, Blue white balance multipliers
uniform int u_ColorSpace; // 0=RGB, 1=LAB, 2=YUV, 3=DIRECT_PCA, 4=RAW_NORMAL
uniform float u_Gamma;
uniform float u_Contrast;

#define LAB_DELTA_CB 0.00885645167
#define LAB_INV_3_DELTA_SQ 7.787037037
#define LAB_16_DIV_116 0.13793103448
#define XN 0.95047
#define YN 1.00000
#define ZN 1.08883

float getCalibratedVal(ivec2 p, float bl) {
    uint rawUint = texelFetch(u_RawTexture, p, 0).r;
    float rawVal = float(rawUint);
    float normRange = max(1.0, u_WhiteLevel - bl);
    return clamp((rawVal - bl) / normRange, 0.0, 1.0);
}

// Fast 2x2 Bayer demosaic: 4 fetches per quad, completely branch-free and robust
vec3 demosaicQuad(ivec2 p, int pattern) {
    ivec2 maxCoord = ivec2(u_TextureSize) - ivec2(2, 2);
    ivec2 q = clamp(p & (~1), ivec2(0, 0), maxCoord);

    float p00 = getCalibratedVal(q, u_BlackLevel.x);
    float p10 = getCalibratedVal(q + ivec2(1, 0), u_BlackLevel.y);
    float p01 = getCalibratedVal(q + ivec2(0, 1), u_BlackLevel.z);
    float p11 = getCalibratedVal(q + ivec2(1, 1), u_BlackLevel.w);

    float r = 0.0;
    float g = 0.0;
    float b = 0.0;

    if (pattern == 0) {
        // RGGB
        r = p00;
        g = (p10 + p01) * 0.5;
        b = p11;
    } else if (pattern == 1) {
        // GRBG
        r = p10;
        g = (p00 + p11) * 0.5;
        b = p01;
    } else if (pattern == 2) {
        // GBRG
        r = p01;
        g = (p00 + p11) * 0.5;
        b = p10;
    } else {
        // BGGR
        r = p11;
        g = (p10 + p01) * 0.5;
        b = p00;
    }

    // Apply Auto White Balance Gains
    vec3 wb = max(vec3(0.01), u_WbGains);
    r = clamp(r * wb.r, 0.0, 1.0);
    g = clamp(g * wb.g, 0.0, 1.0);
    b = clamp(b * wb.b, 0.0, 1.0);

    return vec3(r, g, b);
}

float labF(float t) {
    if (t > LAB_DELTA_CB) {
        return pow(max(0.00001, t), 0.333333333);
    } else {
        return t * LAB_INV_3_DELTA_SQ + LAB_16_DIV_116;
    }
}

float labInvF(float t) {
    if (t > 0.20689655) {
        return t * t * t;
    } else {
        return (t - LAB_16_DIV_116) * 0.12841855;
    }
}

vec3 rgbToLab(vec3 rgb) {
    float X = 0.4124564 * rgb.r + 0.3575761 * rgb.g + 0.1804375 * rgb.b;
    float Y = 0.2126729 * rgb.r + 0.7151522 * rgb.g + 0.0721750 * rgb.b;
    float Z = 0.0193339 * rgb.r + 0.1191920 * rgb.g + 0.9503041 * rgb.b;

    float fx = labF(max(0.0, X / XN));
    float fy = labF(max(0.0, Y / YN));
    float fz = labF(max(0.0, Z / ZN));

    float L = 116.0 * fy - 16.0;
    float a = 500.0 * (fx - fy);
    float b = 200.0 * (fy - fz);

    return vec3(L * 0.01, (a + 128.0) / 255.0, (b + 128.0) / 255.0);
}

vec3 labToRgb(vec3 lab) {
    float L = lab.x * 100.0;
    float a = lab.y * 255.0 - 128.0;
    float b = lab.z * 255.0 - 128.0;

    float fy = (L + 16.0) / 116.0;
    float fx = a / 500.0 + fy;
    float fz = fy - b / 200.0;

    float X = XN * labInvF(fx);
    float Y = YN * labInvF(fy);
    float Z = ZN * labInvF(fz);

    float r =  3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z;
    float g = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z;
    float bl = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z;

    return vec3(r, g, bl);
}

vec3 rgbToYuv(vec3 rgb) {
    float y = 0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
    float u = -0.14713 * rgb.r - 0.28886 * rgb.g + 0.436 * rgb.b + 0.5;
    float v = 0.615 * rgb.r - 0.51499 * rgb.g - 0.10001 * rgb.b + 0.5;
    return vec3(y, u, v);
}

vec3 yuvToRgb(vec3 yuv) {
    float y = yuv.x;
    float u = yuv.y - 0.5;
    float v = yuv.z - 0.5;
    float r = y + 1.13983 * v;
    float g = y - 0.39465 * u - 0.58060 * v;
    float b = y + 2.03211 * u;
    return vec3(r, g, b);
}

void main() {
    if (u_TextureSize.x <= 1.0 || u_TextureSize.y <= 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    ivec2 pixelCoord = ivec2(v_TexCoord * u_TextureSize);
    vec3 linearRgb = demosaicQuad(pixelCoord, u_CfaPattern);

    if (u_ColorSpace == 4) {
        float invGamma = 1.0 / max(0.1, u_Gamma);
        vec3 normalDisplay = pow(clamp(linearRgb, 0.0, 1.0), vec3(invGamma));
        fragColor = vec4(normalDisplay, 1.0);
        return;
    }

    vec3 colorSpaceCoord = linearRgb;
    if (u_ColorSpace == 1) {
        colorSpaceCoord = rgbToLab(linearRgb);
    } else if (u_ColorSpace == 2) {
        colorSpaceCoord = rgbToYuv(linearRgb);
    }

    // Apply affine decorrelation stretch: y = W * x + b
    vec3 stretched = u_DecorrelationMatrix * colorSpaceCoord + u_DecorrelationBias;

    vec3 displayLinear = stretched;
    if (u_ColorSpace == 1) {
        displayLinear = labToRgb(stretched);
    } else if (u_ColorSpace == 2) {
        displayLinear = yuvToRgb(stretched);
    }

    displayLinear = (displayLinear - 0.5) * u_Contrast + 0.5;
    float invGamma = 1.0 / max(0.1, u_Gamma);
    vec3 outRgb = pow(clamp(displayLinear, 0.0, 1.0), vec3(invGamma));
    fragColor = vec4(outRgb, 1.0);
}
