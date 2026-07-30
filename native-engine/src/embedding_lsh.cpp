#include "edgegallery/embedding_lsh.hpp"

#include <algorithm>
#include <stdexcept>
#include <unordered_set>

namespace edgegallery {
namespace {

std::size_t choose_signature_bits(std::size_t expected_items) {
    std::size_t bits = 0;
    std::size_t bucket_count = 1;
    while (bucket_count < expected_items && bits < 16) {
        bucket_count <<= 1;
        ++bits;
    }
    if (bits < 8) return 8;
    if (bits > 16) return 16;
    return bits;
}

std::uint64_t next_random(std::uint64_t& state) {
    // xorshift64*: deterministic on every platform and sufficient for fixed
    // random projection signs (this is not used for cryptography).
    state ^= state >> 12;
    state ^= state << 25;
    state ^= state >> 27;
    return state * 0x2545F4914F6CDD1DULL;
}

}  // namespace

EmbeddingLsh::EmbeddingLsh(
    std::size_t dimension,
    std::size_t expected_items,
    std::size_t table_count,
    std::size_t candidate_limit)
    : dimension_(dimension),
      table_count_(table_count),
      signature_bits_(choose_signature_bits(expected_items)),
      candidate_limit_(candidate_limit) {
    if (dimension == 0) {
        throw std::invalid_argument("EmbeddingLsh dimension must be positive");
    }
    if (table_count == 0) {
        throw std::invalid_argument("EmbeddingLsh table count must be positive");
    }
    if (candidate_limit == 0) {
        throw std::invalid_argument("EmbeddingLsh candidate limit must be positive");
    }

    tables_.resize(table_count_);
    projections_.resize(table_count_ * signature_bits_ * dimension_);

    std::uint64_t random_state = 0xD1B54A32D192ED03ULL;
    for (auto& projection : projections_) {
        projection = (next_random(random_state) & 1ULL) == 0 ? -1 : 1;
    }
}

std::uint64_t EmbeddingLsh::signature(
    std::size_t table,
    const std::vector<float>& embedding) const {
    std::uint64_t result = 0;
    const std::size_t table_offset = table * signature_bits_ * dimension_;

    for (std::size_t bit = 0; bit < signature_bits_; ++bit) {
        const std::size_t projection_offset = table_offset + bit * dimension_;
        double dot = 0.0;
        for (std::size_t dimension = 0; dimension < dimension_; ++dimension) {
            dot += static_cast<double>(embedding[dimension]) *
                projections_[projection_offset + dimension];
        }
        if (dot >= 0.0) {
            result |= std::uint64_t{1} << bit;
        }
    }
    return result;
}

std::vector<std::size_t> EmbeddingLsh::query_and_insert(
    std::size_t image_index,
    const std::vector<float>& embedding) {
    if (embedding.size() != dimension_) {
        throw std::invalid_argument("EmbeddingLsh embedding dimension does not match");
    }

    std::vector<std::uint64_t> signatures(table_count_);
    std::unordered_set<std::size_t> candidates;
    candidates.reserve(candidate_limit_);

    const auto collect_bucket = [&](std::size_t table, std::uint64_t key) {
        const auto bucket = tables_[table].find(key);
        if (bucket == tables_[table].end()) return;
        for (const std::size_t candidate : bucket->second) {
            candidates.insert(candidate);
            if (candidates.size() >= candidate_limit_) return;
        }
    };

    for (std::size_t table = 0;
         table < table_count_ && candidates.size() < candidate_limit_;
         ++table) {
        const std::uint64_t current_signature = signature(table, embedding);
        signatures[table] = current_signature;

        // Probe the exact bucket and every signature one bit away.
        collect_bucket(table, current_signature);
        for (std::size_t bit = 0;
             bit < signature_bits_ && candidates.size() < candidate_limit_;
             ++bit) {
            collect_bucket(table, current_signature ^ (std::uint64_t{1} << bit));
        }
    }

    // Signatures for skipped tables still need to be calculated before insert.
    for (std::size_t table = 0; table < table_count_; ++table) {
        if (signatures[table] == 0) {
            signatures[table] = signature(table, embedding);
        }
        tables_[table][signatures[table]].push_back(image_index);
    }

    std::vector<std::size_t> result(candidates.begin(), candidates.end());
    std::sort(result.begin(), result.end());
    return result;
}

}  // namespace edgegallery
