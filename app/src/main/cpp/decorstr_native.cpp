#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include "covariance.h"
#include "whitening.h"

#define LOG_TAG "DecorstrNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<decorstr::WhiteningEngine> gWhiteningEngine;
static std::mutex gEngineMutex;

extern "C" {

JNIEXPORT void JNICALL
Java_com_decorstr_engine_NativeDecorrelation_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gWhiteningEngine = std::make_unique<decorstr::WhiteningEngine>();
    LOGI("Native decorstr engine initialized");
}

JNIEXPORT void JNICALL
Java_com_decorstr_engine_NativeDecorrelation_nativeReset(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gWhiteningEngine) {
        gWhiteningEngine->reset();
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_decorstr_engine_NativeDecorrelation_nativeProcessBayer16(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject byteBuffer,
    jint width,
    jint height,
    jint rowStridePixels,
    jint bayerPattern,
    jfloatArray jBlackLevels,
    jfloat whiteLevel,
    jfloatArray jWbGains,
    jint colorSpaceMode,
    jfloat alphaSmoothing,
    jboolean lockTransform,
    jfloat targetSigma)
{
    const uint16_t* rawData = static_cast<const uint16_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!rawData) {
        LOGE("Failed to get direct buffer address");
        return nullptr;
    }

    jfloat blackLevels[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    if (jBlackLevels != nullptr && env->GetArrayLength(jBlackLevels) >= 4) {
        env->GetFloatArrayRegion(jBlackLevels, 0, 4, blackLevels);
    }

    jfloat wbGains[3] = {1.0f, 1.0f, 1.0f};
    if (jWbGains != nullptr && env->GetArrayLength(jWbGains) >= 3) {
        env->GetFloatArrayRegion(jWbGains, 0, 3, wbGains);
    }

    auto mode = static_cast<decorstr::ColorSpaceMode>(colorSpaceMode);

    decorstr::CovarianceResult covRes = decorstr::estimateCovarianceBayer16(
        rawData, width, height, rowStridePixels, bayerPattern,
        blackLevels, whiteLevel, wbGains, mode
    );

    decorstr::WhiteningResult whitened;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (!gWhiteningEngine) {
            gWhiteningEngine = std::make_unique<decorstr::WhiteningEngine>();
        }
        whitened = gWhiteningEngine->updateAndCompute(
            covRes, alphaSmoothing, lockTransform, mode, targetSigma
        );
    }

    // Return 15 floats: [matrix (9), bias (3), eigenvalues (3)]
    jfloatArray result = env->NewFloatArray(15);
    if (!result) return nullptr;

    jfloat outData[15];
    if (whitened.valid) {
        for (int i = 0; i < 9; ++i) outData[i] = whitened.matrix[i];
        for (int i = 0; i < 3; ++i) outData[9 + i] = whitened.bias[i];
        for (int i = 0; i < 3; ++i) outData[12 + i] = whitened.eigenvalues[i];
    } else {
        // Fallback to identity
        outData[0] = 1.0f; outData[1] = 0.0f; outData[2] = 0.0f;
        outData[3] = 0.0f; outData[4] = 1.0f; outData[5] = 0.0f;
        outData[6] = 0.0f; outData[7] = 0.0f; outData[8] = 1.0f;
        outData[9] = 0.0f; outData[10] = 0.0f; outData[11] = 0.0f;
        outData[12] = 1.0f; outData[13] = 1.0f; outData[14] = 1.0f;
    }

    env->SetFloatArrayRegion(result, 0, 15, outData);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_decorstr_engine_NativeDecorrelation_nativeProcessBayer10(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject byteBuffer,
    jint width,
    jint height,
    jint rowStrideBytes,
    jint bayerPattern,
    jfloatArray jBlackLevels,
    jfloat whiteLevel,
    jfloatArray jWbGains,
    jint colorSpaceMode,
    jfloat alphaSmoothing,
    jboolean lockTransform,
    jfloat targetSigma)
{
    const uint8_t* rawData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!rawData) {
        LOGE("Failed to get direct buffer address for RAW10");
        return nullptr;
    }

    jfloat blackLevels[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    if (jBlackLevels != nullptr && env->GetArrayLength(jBlackLevels) >= 4) {
        env->GetFloatArrayRegion(jBlackLevels, 0, 4, blackLevels);
    }

    jfloat wbGains[3] = {1.0f, 1.0f, 1.0f};
    if (jWbGains != nullptr && env->GetArrayLength(jWbGains) >= 3) {
        env->GetFloatArrayRegion(jWbGains, 0, 3, wbGains);
    }

    auto mode = static_cast<decorstr::ColorSpaceMode>(colorSpaceMode);

    decorstr::CovarianceResult covRes = decorstr::estimateCovarianceBayer10(
        rawData, width, height, rowStrideBytes, bayerPattern,
        blackLevels, whiteLevel, wbGains, mode
    );

    decorstr::WhiteningResult whitened;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (!gWhiteningEngine) {
            gWhiteningEngine = std::make_unique<decorstr::WhiteningEngine>();
        }
        whitened = gWhiteningEngine->updateAndCompute(
            covRes, alphaSmoothing, lockTransform, mode, targetSigma
        );
    }

    jfloatArray result = env->NewFloatArray(15);
    if (!result) return nullptr;

    jfloat outData[15];
    if (whitened.valid) {
        for (int i = 0; i < 9; ++i) outData[i] = whitened.matrix[i];
        for (int i = 0; i < 3; ++i) outData[9 + i] = whitened.bias[i];
        for (int i = 0; i < 3; ++i) outData[12 + i] = whitened.eigenvalues[i];
    } else {
        outData[0] = 1.0f; outData[1] = 0.0f; outData[2] = 0.0f;
        outData[3] = 0.0f; outData[4] = 1.0f; outData[5] = 0.0f;
        outData[6] = 0.0f; outData[7] = 0.0f; outData[8] = 1.0f;
        outData[9] = 0.0f; outData[10] = 0.0f; outData[11] = 0.0f;
        outData[12] = 1.0f; outData[13] = 1.0f; outData[14] = 1.0f;
    }

    env->SetFloatArrayRegion(result, 0, 15, outData);
    return result;
}

} // extern "C"
