#include "eigen3x3.h"
#include <cmath>
#include <algorithm>

namespace decorstr {

Eigen3x3Result solveSymmetricEigen3x3(const float cov[9], const float* prevEigenvectors) {
    Eigen3x3Result res;

    // Working copies
    float A[3][3];
    A[0][0] = cov[0]; A[0][1] = cov[1]; A[0][2] = cov[2];
    A[1][0] = cov[3]; A[1][1] = cov[4]; A[1][2] = cov[5];
    A[2][0] = cov[6]; A[2][1] = cov[7]; A[2][2] = cov[8];

    // Identity matrix for eigenvectors
    float V[3][3] = {
        {1.0f, 0.0f, 0.0f},
        {0.0f, 1.0f, 0.0f},
        {0.0f, 0.0f, 1.0f}
    };

    constexpr int MAX_ITER = 30;
    constexpr float EPS = 1e-9f;

    for (int iter = 0; iter < MAX_ITER; ++iter) {
        // Find largest off-diagonal element
        int p = 0, q = 1;
        float maxOff = std::abs(A[0][1]);
        if (std::abs(A[0][2]) > maxOff) {
            p = 0; q = 2; maxOff = std::abs(A[0][2]);
        }
        if (std::abs(A[1][2]) > maxOff) {
            p = 1; q = 2; maxOff = std::abs(A[1][2]);
        }

        if (maxOff < EPS) {
            break;
        }

        // Jacobi rotation angle
        float theta = 0.5f * (A[q][q] - A[p][p]) / A[p][q];
        float t = (theta >= 0.0f) ?
            (1.0f / (theta + std::sqrt(theta * theta + 1.0f))) :
            (-1.0f / (-theta + std::sqrt(theta * theta + 1.0f)));
        float c = 1.0f / std::sqrt(t * t + 1.0f);
        float s = t * c;

        // Apply rotation to A
        float app = A[p][p];
        float aqq = A[q][q];
        float apq = A[p][q];

        A[p][p] = c * c * app - 2.0f * s * c * apq + s * s * aqq;
        A[q][q] = s * s * app + 2.0f * s * c * apq + c * c * aqq;
        A[p][q] = A[q][p] = 0.0f;

        for (int r = 0; r < 3; ++r) {
            if (r != p && r != q) {
                float arp = A[r][p];
                float arq = A[r][q];
                A[r][p] = A[p][r] = c * arp - s * arq;
                A[r][q] = A[q][r] = s * arp + c * arq;
            }
        }

        // Apply rotation to V
        for (int r = 0; r < 3; ++r) {
            float vrp = V[r][p];
            float vrq = V[r][q];
            V[r][p] = c * vrp - s * vrq;
            V[r][q] = s * vrp + c * vrq;
        }
    }

    // Extract eigenvalues and eigenvector columns
    float lambdas[3] = {A[0][0], A[1][1], A[2][2]};
    int indices[3] = {0, 1, 2};

    // Sort descending by eigenvalue
    if (lambdas[indices[0]] < lambdas[indices[1]]) std::swap(indices[0], indices[1]);
    if (lambdas[indices[1]] < lambdas[indices[2]]) std::swap(indices[1], indices[2]);
    if (lambdas[indices[0]] < lambdas[indices[1]]) std::swap(indices[0], indices[1]);

    for (int i = 0; i < 3; ++i) {
        int idx = indices[i];
        res.eigenvalues[i] = std::max(0.0f, lambdas[idx]);
        // Column i gets V's column idx
        res.eigenvectors[0 * 3 + i] = V[0][idx];
        res.eigenvectors[1 * 3 + i] = V[1][idx];
        res.eigenvectors[2 * 3 + i] = V[2][idx];
    }

    // Align signs against previous frame's eigenvectors if available
    if (prevEigenvectors != nullptr) {
        for (int i = 0; i < 3; ++i) {
            float dot = res.eigenvectors[0 * 3 + i] * prevEigenvectors[0 * 3 + i] +
                        res.eigenvectors[1 * 3 + i] * prevEigenvectors[1 * 3 + i] +
                        res.eigenvectors[2 * 3 + i] * prevEigenvectors[2 * 3 + i];
            if (dot < 0.0f) {
                res.eigenvectors[0 * 3 + i] = -res.eigenvectors[0 * 3 + i];
                res.eigenvectors[1 * 3 + i] = -res.eigenvectors[1 * 3 + i];
                res.eigenvectors[2 * 3 + i] = -res.eigenvectors[2 * 3 + i];
            }
        }
    }

    // Check determinant: det(V)
    float det = res.eigenvectors[0] * (res.eigenvectors[4] * res.eigenvectors[8] - res.eigenvectors[5] * res.eigenvectors[7])
              - res.eigenvectors[1] * (res.eigenvectors[3] * res.eigenvectors[8] - res.eigenvectors[5] * res.eigenvectors[6])
              + res.eigenvectors[2] * (res.eigenvectors[3] * res.eigenvectors[7] - res.eigenvectors[4] * res.eigenvectors[6]);

    if (det < 0.0f) {
        // Flip third eigenvector to preserve right-handed coordinate system
        res.eigenvectors[0 * 3 + 2] = -res.eigenvectors[0 * 3 + 2];
        res.eigenvectors[1 * 3 + 2] = -res.eigenvectors[1 * 3 + 2];
        res.eigenvectors[2 * 3 + 2] = -res.eigenvectors[2 * 3 + 2];
    }

    res.success = true;
    return res;
}

} // namespace decorstr
