#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace edgegallery {

// The Android layer populates this structure after feature extraction.
// Empty content hashes and missing embeddings are treated as unknown.
struct ImageFingerprint {
    std::string id;
    std::string content_hash;
    std::uint64_t perceptual_hash = 0;
    bool has_perceptual_hash = false;
    std::vector<float> embedding;
};

enum class DuplicateKind {
    Exact,
    ModifiedCopy,
    Related,
};

struct DuplicateGroup {
    DuplicateKind kind;
    std::vector<std::string> member_ids;
};

struct ClusterOptions {
    std::uint32_t hamming_threshold = 8;
    // Higher cosine threshold is more conservative. Both thresholds must be
    // calibrated with a labelled dataset before relying on the grouping.
    float similarity_threshold = 0.85f;
    bool include_singletons = false;
};

std::uint32_t hamming_distance(std::uint64_t left, std::uint64_t right) noexcept;

// Returns the cosine similarity between two embedding vectors.
// Both vectors must have the same size. Returns 0.0 if either is empty.
float cosine_similarity(const std::vector<float>& left,
                        const std::vector<float>& right) noexcept;

// Groups exact matches first, then reports dHash-based modified copies and
// embedding-based related photos separately. Output order follows the first
// appearance in the input, which keeps results stable for the UI and tests.
//
// Throws std::invalid_argument for an empty/duplicate id or an invalid threshold.
std::vector<DuplicateGroup> cluster_duplicates(
    const std::vector<ImageFingerprint>& images,
    const ClusterOptions& options = {});

}  // namespace edgegallery
