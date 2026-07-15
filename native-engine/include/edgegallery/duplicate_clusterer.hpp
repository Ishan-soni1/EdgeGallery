#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace edgegallery {

// The Android layer can populate this structure after feature extraction.
// Empty content hashes and missing perceptual hashes are treated as unknown.
struct ImageFingerprint {
    std::string id;
    std::string content_hash;
    std::uint64_t perceptual_hash = 0;
    bool has_perceptual_hash = false;
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
    // A smaller threshold is more conservative. dHash commonly starts near 8,
    // but the final value should be selected using a labelled dataset.
    std::uint32_t hamming_threshold = 8;
    bool include_singletons = false;
};

// Returns the number of different bits in two 64-bit perceptual hashes.
std::uint32_t hamming_distance(std::uint64_t left, std::uint64_t right) noexcept;

// Groups exact matches first, then joins visually similar images using a
// Disjoint Set Union. Output order follows the first appearance in the input,
// which keeps results stable for the UI and tests.
//
// Throws std::invalid_argument for an empty/duplicate id or a threshold > 64.
std::vector<DuplicateGroup> cluster_duplicates(
    const std::vector<ImageFingerprint>& images,
    const ClusterOptions& options = {});

}  // namespace edgegallery
