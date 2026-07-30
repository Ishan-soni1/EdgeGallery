#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <unordered_map>
#include <vector>

namespace edgegallery {

/**
 * Multi-Index Hashing for fast Hamming-radius search on 64-bit binary codes.
 *
 * Based on the pigeonhole principle:
 *   If two 64-bit hashes differ in at most `r` positions and are split into
 *   `m = r + 1` blocks, then at least one block must be identical.
 *
 * The index builds one hash table per block.  A query retrieves all entries
 * sharing at least one block with the query hash, then verifies each candidate
 * with a full Hamming-distance check.
 *
 * Reference: Norouzi, Punjani & Fleet — "Fast Search in Hamming Space with
 *            Multi-Index Hashing" (TPAMI 2014).
 */
class MultiIndexHash {
public:
    /// Maximum supported Hamming threshold.  Determines the number of blocks.
    static constexpr std::uint32_t kMaxThreshold = 15;

    /// @param threshold  Maximum Hamming distance to consider a match.
    ///                   Must be in [1, kMaxThreshold].
    explicit MultiIndexHash(std::uint32_t threshold);

    /// Inserts an image into the index.
    void insert(std::size_t image_index, std::uint64_t hash);

    /// Returns image indices whose stored hash has Hamming distance
    /// ≤ threshold from @p query_hash. Work and results are bounded by
    /// @p max_checks; results are deduplicated.
    std::vector<std::size_t> query(
        std::uint64_t query_hash,
        std::size_t max_checks = std::numeric_limits<std::size_t>::max()) const;

    /// Number of blocks the hash is split into.
    std::uint32_t block_count() const { return block_count_; }

private:
    std::uint32_t threshold_;
    std::uint32_t block_count_;   // = threshold_ + 1
    std::uint32_t bits_per_block_;

    struct Entry {
        std::size_t image_index;
        std::uint64_t full_hash;
    };

    // One table per block.  Key = extracted block value.
    std::vector<std::unordered_map<std::uint32_t, std::vector<Entry>>> tables_;

    std::uint32_t extract_block(std::uint64_t hash, std::uint32_t block_id) const;
};

}  // namespace edgegallery
