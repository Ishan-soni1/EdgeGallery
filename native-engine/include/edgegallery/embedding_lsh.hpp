#pragma once

#include <cstddef>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace edgegallery {

/**
 * A small cosine-locality index for neural embeddings.
 *
 * Each table projects an embedding onto a handful of deterministic random
 * hyperplanes. The signs form a short binary signature. Similar vectors are
 * likely to share the same signature, or one that differs by a single bit.
 *
 * Queries only inspect those nearby buckets and return a bounded number of
 * candidates. With fixed table and candidate limits, each photo does a fixed
 * amount of work instead of scanning every earlier photo.
 */
class EmbeddingLsh {
public:
    EmbeddingLsh(std::size_t dimension,
                 std::size_t expected_items,
                 std::size_t table_count = 12,
                 std::size_t candidate_limit = 256);

    /// Returns likely earlier neighbours, then inserts this embedding.
    std::vector<std::size_t> query_and_insert(
        std::size_t image_index,
        const std::vector<float>& embedding);

    std::size_t signature_bits() const noexcept { return signature_bits_; }
    std::size_t candidate_limit() const noexcept { return candidate_limit_; }

private:
    std::size_t dimension_;
    std::size_t table_count_;
    std::size_t signature_bits_;
    std::size_t candidate_limit_;

    // Values are -1 or +1. Keeping them as bytes makes the index compact.
    std::vector<std::int8_t> projections_;
    std::vector<std::unordered_map<std::uint64_t, std::vector<std::size_t>>> tables_;

    std::uint64_t signature(
        std::size_t table,
        const std::vector<float>& embedding) const;
};

}  // namespace edgegallery
