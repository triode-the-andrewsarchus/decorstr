#include "whitening.h"
#include <cmath>
#include <algorithm>
#include <cstring>

namespace decorstr {

WhiteningEngine::WhiteningEngine() {
    reset();
}

void WhiteningEngine::reset() {
    std::memset(mSmoothCov, 0, sizeof(mSmoothCov));
    std::memset(mSmoothMean, 0, sizeof(mSmoothMean));
    std::memset(mPrevEigenvectors, 0, sizeof(mPrevEigenvectors));
    mInitialized = false;
}

WhiteningResult WhiteningEngine::updateAndCompute(
    const CovarianceResult& rawCov,
    float alphaSmoothing,
    bool lockTransform,
    ColorSpaceMode mode,
    float targetSigma,
    const float targetMean[3])
{
    WhiteningResult res;
    if (!rawCov.valid) {
        return res;
    }

    if (mode == ColorSpaceMode::RAW_NORMAL) {
        // Identity
        res.matrix[0] = 1.0f; res.matrix[4] = 1.0f; res.matrix[8] = 1.0f;
        res.bias[0] = 0.0f; res.bias[1] = 0.0f; res.bias[2] = 0.0f;
        res.valid = true;
        return res;
    }

    alphaSmoothing = std::clamp(alphaSmoothing, 0.001f, 1.0f);

    // 1. Temporal Smoothing on Covariance and Mean
    if (!mInitialized) {
        std::memcpy(mSmoothCov, rawCov.cov, sizeof(mSmoothCov));
        std::memcpy(mSmoothMean, rawCov.mean, sizeof(mSmoothMean));
        mInitialized = true;
    } else if (!lockTransform) {
        for (int i = 0; i < 9; ++i) {
            mSmoothCov[i] = (1.0f - alphaSmoothing) * mSmoothCov[i] + alphaSmoothing * rawCov.cov[i];
        }
        for (int i = 0; i < 3; ++i) {
            mSmoothMean[i] = (1.0f - alphaSmoothing) * mSmoothMean[i] + alphaSmoothing * rawCov.mean[i];
        }
    }

    // 2. Eigendecomposition with phase tracking
    Eigen3x3Result eigen = solveSymmetricEigen3x3(mSmoothCov, mPrevEigenvectors);
    if (!eigen.success) {
        return res;
    }

    // Save eigenvectors for next frame alignment
    std::memcpy(mPrevEigenvectors, eigen.eigenvectors, sizeof(mPrevEigenvectors));
    std::memcpy(res.eigenvalues, eigen.eigenvalues, sizeof(res.eigenvalues));

    // 3. Whitening scales
    float S[3];
    constexpr float EPS = 1e-6f;
    for (int i = 0; i < 3; ++i) {
        S[i] = targetSigma / std::sqrt(std::max(eigen.eigenvalues[i], EPS));
    }

    // 4. Matrix Synthesis
    if (mode == ColorSpaceMode::DIRECT_PCA) {
        // Direct PCA: W = S * V^T
        // Row i of W is S[i] * col i of V transposed
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) {
                res.matrix[i * 3 + j] = S[i] * eigen.eigenvectors[j * 3 + i];
            }
        }
    } else {
        // ZCA Whitening (Mahalanobis): W = V * S * V^T
        // W_jk = sum_m V_jm * S_m * V_km
        for (int j = 0; j < 3; ++j) {
            for (int k = 0; k < 3; ++k) {
                float val = 0.0f;
                for (int m = 0; m < 3; ++m) {
                    val += eigen.eigenvectors[j * 3 + m] * S[m] * eigen.eigenvectors[k * 3 + m];
                }
                res.matrix[j * 3 + k] = val;
            }
        }
    }

    // 5. Target Mean and Bias calculation: b = mu_target - W * mu_smooth
    if (mode == ColorSpaceMode::RGB0) {
        // RGB0: Zero mean / zero bias -> preserves deep blacks and shadows
        res.bias[0] = 0.0f;
        res.bias[1] = 0.0f;
        res.bias[2] = 0.0f;
    } else {
        float defaultTarget[3] = {0.5f, 0.5f, 0.5f};
        const float* target = (targetMean != nullptr) ? targetMean : defaultTarget;

        for (int i = 0; i < 3; ++i) {
            float wMu = res.matrix[i * 3 + 0] * mSmoothMean[0]
                      + res.matrix[i * 3 + 1] * mSmoothMean[1]
                      + res.matrix[i * 3 + 2] * mSmoothMean[2];
            res.bias[i] = target[i] - wMu;
        }
    }

    res.valid = true;
    return res;
}

} // namespace decorstr
