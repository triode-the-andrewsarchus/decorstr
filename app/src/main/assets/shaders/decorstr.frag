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
uniform int u_ColorSpace; // 0=RGB, 1=LAB, 2=YUV, 3=DIRECT_PCA, 4=RAW_NORMAL
uniform float u_Gamma;
uniform float u_Contrast;

float sampleCalibrated(ivec2 p) {
    p = clamp(p, ivec2(0, 0), ivec2(u_TextureSize) - ivec2(1, 1));
    uint rawUint = texelFetch(u_RawTexture, p, 0).r;
    float rawVal = float(rawUint);
    int cfaIdx = (p.y % 2) * 2 + (p.x % 2);
    float bl = (cfaIdx == 0) ? u_BlackLevel.x :
               (cfaIdx == 1) ? u_BlackLevel.y :
               (cfaIdx == 2) ? u_BlackLevel.z : u_BlackLevel.w;
    float normRange = max(1.0, u_WhiteLevel - bl);
    return clamp((rawVal - bl) / normRange, 0.0, 1.0);
}

// Bilinear demosaicing on Bayer grid
vec3 demosaicBayer(ivec2 p, int pattern) {
    int xMod = p.x % 2;
    int yMod = p.y % 2;
    int quadIdx = yMod * 2 + xMod; // 0=(0,0), 1=(1,0), 2=(0,1), 3=(1,1)

    // Remap quadIdx to Bayer channel based on pattern:
    // Pattern 0 (RGGB): 0=R, 1=Gr, 2=Gb, 3=B
    // Pattern 1 (GRBG): 0=Gr, 1=R, 2=B, 3=Gb
    // Pattern 2 (GBRG): 0=Gb, 1=B, 2=R, 3=Gr
    // Pattern 3 (BGGR): 0=B, 1=Gb, 2=Gr, 3=R
    int channel;
    if (pattern == 0) {
        channel = (quadIdx == 0) ? 0 : (quadIdx == 3) ? 2 : 1;
    } else if (pattern == 1) {
        channel = (quadIdx == 1) ? 0 : (quadIdx == 2) ? 2 : 1;
    } else if (pattern == 2) {
        channel = (quadIdx == 2) ? 0 : (quadIdx == 1) ? 2 : 1;
    } else {
        channel = (quadIdx == 3) ? 0 : (quadIdx == 0) ? 2 : 1;
    }

    float cCenter = sampleCalibrated(p);
    float r = 0.0, g = 0.0, b = 0.0;

    if (channel == 1) {
        // Pixel is GREEN
        g = cCenter;
        float hAvg = (sampleCalibrated(p + ivec2(-1, 0)) + sampleCalibrated(p + ivec2(1, 0))) * 0.5;
        float vAvg = (sampleCalibrated(p + ivec2(0, -1)) + sampleCalibrated(p + ivec2(0, 1))) * 0.5;

        bool rIsHorizontal = (pattern == 0 && yMod == 0) || (pattern == 1 && yMod == 0) ||
                             (pattern == 2 && yMod == 1) || (pattern == 3 && yMod == 1);
        if (rIsHorizontal) {
            r = hAvg;
            b = vAvg;
        } else {
            r = vAvg;
            b = hAvg;
        }
    } else if (channel == 0) {
        // Pixel is RED
        r = cCenter;
        g = (sampleCalibrated(p + ivec2(-1, 0)) + sampleCalibrated(p + ivec2(1, 0)) +
             sampleCalibrated(p + ivec2(0, -1)) + sampleCalibrated(p + ivec2(0, 1))) * 0.25;
        b = (sampleCalibrated(p + ivec2(-1, -1)) + sampleCalibrated(p + ivec2(1, -1)) +
             sampleCalibrated(p + ivec2(-1, 1))  + sampleCalibrated(p + ivec2(1, 1))) * 0.25;
    } else {
        // Pixel is BLUE
        b = cCenter;
        g = (sampleCalibrated(p + ivec2(-1, 0)) + sampleCalibrated(p + ivec2(1, 0)) +
             sampleCalibrated(p + ivec2(0, -1)) + sampleCalibrated(p + ivec2(0, 1))) * 0.25;
        r = (sampleCalibrated(p + ivec2(-1, -1)) + sampleCalibrated(p + ivec2(1, -1)) +
             sampleCalibrated(p + ivec2(-1, 1))  + sampleCalibrated(p + ivec2(1, 1))) * 0.25;
    }

    return vec3(r, g, b);
}

// CIELAB helpers
float labF(float t) {
    const float delta = 6.0 / 29.0;
    const float delta3 = delta * delta * delta;
    if (t > delta3) {
        return pow(max(0.00001, t), 1.0 / 3.0);
    } else {
        return t / (3.0 * delta * delta) + 4.0 / 29.0;
    }
}

float labInvF(float t) {
    const float delta = 6.0 / 29.0;
    if (t > delta) {
        return t * t * t;
    } else {
        return 3.0 * delta * delta * (t - 4.0 / 29.0);
    }
}

vec3 rgbToLab(vec3 rgb) {
    const float Xn = 0.95047;
    const float Yn = 1.00000;
    const float Zn = 1.08883;

    float X = 0.4124564 * rgb.r + 0.3575761 * rgb.g + 0.1804375 * rgb.b;
    float Y = 0.2126729 * rgb.r + 0.7151522 * rgb.g + 0.0721750 * rgb.b;
    float Z = 0.0193339 * rgb.r + 0.1191920 * rgb.g + 0.9503041 * rgb.b;

    float fx = labF(max(0.0, X / Xn));
    float fy = labF(max(0.0, Y / Yn));
    float fz = labF(max(0.0, Z / Zn));

    float L = 116.0 * fy - 16.0;
    float a = 500.0 * (fx - fy);
    float b = 200.0 * (fy - fz);

    return vec3(L * 0.01, (a + 128.0) / 255.0, (b + 128.0) / 255.0);
}

vec3 labToRgb(vec3 lab) {
    const float Xn = 0.95047;
    const float Yn = 1.00000;
    const float Zn = 1.08883;

    float L = lab.x * 100.0;
    float a = lab.y * 255.0 - 128.0;
    float b = lab.z * 255.0 - 128.0;

    float fy = (L + 16.0) / 116.0;
    float fx = a / 500.0 + fy;
    float fz = fy - b / 200.0;

    float X = Xn * labInvF(fx);
    float Y = Yn * labInvF(fy);
    float Z = Zn * labInvF(fz);

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
    vec3 linearRgb = demosaicBayer(pixelCoord, u_CfaPattern);

    if (u_ColorSpace == 4) {
        vec3 normalDisplay = pow(clamp(linearRgb, 0.0, 1.0), vec3(1.0 / max(0.1, u_Gamma)));
        fragColor = vec4(normalDisplay, 1.0);
        return;
    }

    vec3 colorSpaceCoord;
    if (u_ColorSpace == 1) {
        colorSpaceCoord = rgbToLab(linearRgb);
    } else if (u_ColorSpace == 2) {
        colorSpaceCoord = rgbToYuv(linearRgb);
    } else {
        colorSpaceCoord = linearRgb;
    }

    // Apply affine decorrelation stretch: y = W * x + b
    vec3 stretched = u_DecorrelationMatrix * colorSpaceCoord + u_DecorrelationBias;

    vec3 displayLinear;
    if (u_ColorSpace == 1) {
        displayLinear = labToRgb(stretched);
    } else if (u_ColorSpace == 2) {
        displayLinear = yuvToRgb(stretched);
    } else {
        displayLinear = stretched;
    }

    displayLinear = (displayLinear - 0.5) * u_Contrast + 0.5;
    vec3 outRgb = pow(clamp(displayLinear, 0.0, 1.0), vec3(1.0 / max(0.1, u_Gamma)));
    fragColor = vec4(outRgb, 1.0);
}
