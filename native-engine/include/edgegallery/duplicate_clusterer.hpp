#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace edgegallery {

// The Android layer populates this structure after feature extraction.
// Empty content hashes and missing embeddings are treated as unknown.
struct ImageFingerprint {
    std::string id;
    std::string content_hash;
    std::vector<float> embedding;
};

enum class DuplicateKind {
    Exact,
    VisuallySimilar,
};

struct DuplicateGroup {
    DuplicateKind kind;
    std::vector<std::string> member_ids;
};

struct ClusterOptions {
    // Higher threshold is more conservative. A good starting point for
    // MobileNet embeddings is 0.85, but the final value should be selected
    // using a labelled dataset.
    float similarity_threshold = 0.85f;
    bool include_singletons = false;
};

// Returns the cosine similarity between two embedding vectors.
// Both vectors must have the same size. Returns 0.0 if either is empty.
float cosine_similarity(const std::vector<float>& left,
                        const std::vector<float>& right) noexcept;

// Groups exact matches first, then joins visually similar images using a
// Disjoint Set Union. Output order follows the first appearance in the input,
// which keeps results stable for the UI and tests.
//
// Throws std::invalid_argument for an empty/duplicate id or an invalid threshold.
std::vector<DuplicateGroup> cluster_duplicates(
    const std::vector<ImageFingerprint>& images,
    const ClusterOptions& options = {});

}  // namespace edgegallery
