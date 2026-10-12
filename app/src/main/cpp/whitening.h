#pragma once

#include "color_spaces.h"
#include "covariance.h"
#include "eigen3x3.h"

namespace decorstr {

struct WhiteningResult {
    float matrix[9]{0.0f};  // 3x3 row-major transform matrix W
    float bias[3]{0.0f};    // 3D bias vector b
    float eigenvalues[3]{0.0f};
    bool valid{false};
};

class WhiteningEngine {
public:
    WhiteningEngine();
    void reset();

    WhiteningResult updateAndCompute(
        const CovarianceResult& rawCov,
        float alphaSmoothing,
        bool lockTransform,
        ColorSpaceMode mode,
        float targetSigma = 0.25f,
        const float targetMean[3] = nullptr
    );

private:
    float mSmoothCov[9]{0.0f};
    float mSmoothMean[3]{0.0f};
    float mPrevEigenvectors[9]{0.0f};
    bool mInitialized{false};
};

} // namespace decorstr
