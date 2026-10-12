#pragma once

namespace decorstr {

struct Eigen3x3Result {
    float eigenvalues[3]{0.0f, 0.0f, 0.0f}; // Sorted descending
    float eigenvectors[9]{0.0f};            // Column vectors: [v0, v1, v2]
    bool success{false};
};

// Solves eigendecomposition of a symmetric 3x3 matrix using Jacobi rotations.
// Aligns eigenvector phases against prevEigenvectors (if provided) to prevent sign flips.
Eigen3x3Result solveSymmetricEigen3x3(
    const float cov[9],
    const float* prevEigenvectors = nullptr
);

} // namespace decorstr
