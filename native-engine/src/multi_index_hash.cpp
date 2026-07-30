#include "edgegallery/multi_index_hash.hpp"

#include <algorithm>
#if defined(__has_include)
#if __has_include(<bit>)
#include <bit>
#endif
#endif
#include <stdexcept>
#include <unordered_set>

namespace edgegallery {

namespace {

std::uint32_t popcount64(std::uint64_t value) {
#if defined(__cpp_lib_bitops) && __cpp_lib_bitops >= 201907L
    return static_cast<std::uint32_t>(std::popcount(value));
#else
    // Portable Hamming-weight (Kernighan's method).
    std::uint32_t count = 0;
    while (value != 0) {
        value &= value - 1;
        ++count;
    }
    return count;
#endif
}

}  // namespace

MultiIndexHash::MultiIndexHash(std::uint32_t threshold)
    : threshold_(threshold),
      block_count_(threshold + 1),
      bits_per_block_(64 / (threshold + 1)) {
    if (threshold == 0 || threshold > kMaxThreshold) {
        throw std::invalid_argument(
            "MultiIndexHash threshold must be in [1, " +
            std::to_string(kMaxThreshold) + "]");
    }
    tables_.resize(block_count_);
}

std::uint32_t MultiIndexHash::extract_block(
    std::uint64_t hash, std::uint32_t block_id) const {
    // The last block absorbs any leftover bits when 64 is not evenly divisible.
    const std::uint32_t start_bit = block_id * bits_per_block_;
    const std::uint32_t width =
        (block_id == block_count_ - 1)
            ? (64 - start_bit)
            : bits_per_block_;
    const std::uint64_t mask = (width >= 64)
        ? ~std::uint64_t{0}
        : (std::uint64_t{1} << width) - 1;
    return static_cast<std::uint32_t>((hash >> start_bit) & mask);
}

void MultiIndexHash::insert(std::size_t image_index, std::uint64_t hash) {
    for (std::uint32_t b = 0; b < block_count_; ++b) {
        const std::uint32_t key = extract_block(hash, b);
        tables_[b][key].push_back({image_index, hash});
    }
}

std::vector<std::size_t> MultiIndexHash::query(
    std::uint64_t query_hash,
    std::size_t max_checks) const {
    if (max_checks == 0) return {};

    // Collect candidate image indices that share at least one block.
    std::unordered_set<std::size_t> seen;
    std::size_t checks = 0;

    for (std::uint32_t b = 0; b < block_count_; ++b) {
        const std::uint32_t key = extract_block(query_hash, b);
        const auto it = tables_[b].find(key);
        if (it == tables_[b].end()) continue;

        for (const auto& entry : it->second) {
            if (checks >= max_checks) {
                std::vector<std::size_t> result(seen.begin(), seen.end());
                std::sort(result.begin(), result.end());
                return result;
            }
            ++checks;

            // Full Hamming-distance verification.
            if (popcount64(query_hash ^ entry.full_hash) <= threshold_) {
                seen.insert(entry.image_index);
            }
        }
    }

    std::vector<std::size_t> result(seen.begin(), seen.end());
    std::sort(result.begin(), result.end());
    return result;
}

}  // namespace edgegallery
