#include "edgegallery/duplicate_clusterer.hpp"
#include "edgegallery/disjoint_set.hpp"
#include "edgegallery/embedding_lsh.hpp"
#include "edgegallery/multi_index_hash.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace edgegallery {
namespace {

constexpr std::size_t kPerceptualCandidateLimit = 256;

/// Returns true when two images pass the metadata pre-filter.
/// Skipped when either image has no metadata (width/height == 0).
bool passes_metadata_filter(const ImageFingerprint& left,
                            const ImageFingerprint& right) {
    if (left.width <= 0 || left.height <= 0 ||
        right.width <= 0 || right.height <= 0) {
        return true;  // No metadata — can't filter, so allow.
    }

    // Aspect-ratio check: reject if ratios differ by more than 20%.
    const double ar_left = static_cast<double>(left.width) / left.height;
    const double ar_right = static_cast<double>(right.width) / right.height;
    const double ar_ratio = (ar_left > ar_right)
        ? ar_left / ar_right
        : ar_right / ar_left;
    if (ar_ratio > 1.20) return false;

    // File-size check: reject if sizes differ by more than 10x.
    if (left.file_size > 0 && right.file_size > 0) {
        const auto bigger = std::max(left.file_size, right.file_size);
        const auto smaller = std::min(left.file_size, right.file_size);
        if (bigger > smaller * 10) return false;
    }

    return true;
}

}  // namespace

std::uint32_t hamming_distance(std::uint64_t left, std::uint64_t right) noexcept {
    std::uint64_t difference = left ^ right;
    std::uint32_t distance = 0;
    while (difference != 0) {
        difference &= difference - 1;
        ++distance;
    }
    return distance;
}

float cosine_similarity(
    const std::vector<float>& left,
    const std::vector<float>& right) noexcept {
    if (left.empty() || right.empty() || left.size() != right.size()) {
        return 0.0f;
    }

    double dot = 0.0;
    double magnitude_left = 0.0;
    double magnitude_right = 0.0;
    for (std::size_t index = 0; index < left.size(); ++index) {
        dot += static_cast<double>(left[index]) * right[index];
        magnitude_left += static_cast<double>(left[index]) * left[index];
        magnitude_right += static_cast<double>(right[index]) * right[index];
    }

    const double denominator = std::sqrt(magnitude_left) * std::sqrt(magnitude_right);
    if (denominator == 0.0) {
        return 0.0f;
    }
    return static_cast<float>(dot / denominator);
}

std::vector<DuplicateGroup> cluster_duplicates(
    const std::vector<ImageFingerprint>& images,
    const ClusterOptions& options,
    ClusterStats* stats) {
    if (stats != nullptr) {
        *stats = {};
    }
    if (options.hamming_threshold > MultiIndexHash::kMaxThreshold) {
        throw std::invalid_argument("hamming_threshold must be between 0 and 15");
    }
    if (!std::isfinite(options.similarity_threshold) ||
        options.similarity_threshold < 0.0f ||
        options.similarity_threshold > 1.0f) {
        throw std::invalid_argument("similarity_threshold must be between 0.0 and 1.0");
    }

    std::unordered_set<std::string> ids;
    ids.reserve(images.size());
    std::size_t embedding_dimension = 0;
    for (const auto& image : images) {
        if (image.id.empty()) {
            throw std::invalid_argument("image id must not be empty");
        }
        if (!ids.insert(image.id).second) {
            throw std::invalid_argument("image ids must be unique");
        }
        if (!image.embedding.empty()) {
            if (embedding_dimension == 0) {
                embedding_dimension = image.embedding.size();
            } else if (image.embedding.size() != embedding_dimension) {
                throw std::invalid_argument("embedding dimensions must match");
            }
            if (!std::all_of(image.embedding.begin(), image.embedding.end(), [](float value) {
                    return std::isfinite(value);
                })) {
                throw std::invalid_argument("embeddings must contain finite values");
            }
        }
    }

    // ---------------------------------------------------------------
    // Phase 0 (unchanged): Exact SHA-256 grouping  — O(n)
    // ---------------------------------------------------------------
    std::unordered_map<std::string, std::size_t> exact_group_by_hash;
    exact_group_by_hash.reserve(images.size());
    std::vector<std::vector<std::size_t>> exact_groups;
    for (std::size_t index = 0; index < images.size(); ++index) {
        const auto& content_hash = images[index].content_hash;
        if (content_hash.empty()) {
            continue;
        }

        const auto insertion = exact_group_by_hash.emplace(content_hash, exact_groups.size());
        if (insertion.second) {
            exact_groups.push_back({index});
        } else {
            exact_groups[insertion.first->second].push_back(index);
        }
    }

    std::vector<DuplicateGroup> result;
    for (const auto& members : exact_groups) {
        if (members.size() < 2) {
            continue;
        }
        DuplicateGroup group{DuplicateKind::Exact, {}};
        group.member_ids.reserve(members.size());
        for (const std::size_t index : members) {
            group.member_ids.push_back(images[index].id);
        }
        result.push_back(std::move(group));
    }

    // Build representative list (one per SHA-256).
    std::unordered_set<std::string> represented_hashes;
    represented_hashes.reserve(images.size());
    std::vector<std::size_t> representatives;
    representatives.reserve(images.size());
    for (std::size_t index = 0; index < images.size(); ++index) {
        const auto& content_hash = images[index].content_hash;
        if (content_hash.empty() || represented_hashes.insert(content_hash).second) {
            representatives.push_back(index);
        }
    }

    // ---------------------------------------------------------------
    // Phase 1: Multi-Index Hashing for dHash near-duplicates  — O(n·m)
    // ---------------------------------------------------------------
    if (options.hamming_threshold > 0) {
        // Collect representatives that have a valid perceptual hash.
        std::vector<std::size_t> hashable_reps;
        hashable_reps.reserve(representatives.size());
        for (const std::size_t rep : representatives) {
            if (images[rep].has_perceptual_hash) {
                hashable_reps.push_back(rep);
            }
        }

        if (hashable_reps.size() >= 2) {
            MultiIndexHash mih(options.hamming_threshold);
            DisjointSet dsu(hashable_reps.size());
            for (std::size_t k = 0; k < hashable_reps.size(); ++k) {
                const auto candidates = mih.query(
                    images[hashable_reps[k]].perceptual_hash,
                    kPerceptualCandidateLimit);
                for (const std::size_t candidate_k : candidates) {
                    // Phase 2: metadata pre-filter.
                    if (!passes_metadata_filter(
                            images[hashable_reps[k]],
                            images[hashable_reps[candidate_k]])) {
                        continue;
                    }
                    dsu.unite(k, candidate_k);
                }
                mih.insert(k, images[hashable_reps[k]].perceptual_hash);
            }

            for (auto& local_group : dsu.groups(/*min_size=*/2)) {
                DuplicateGroup group{DuplicateKind::ModifiedCopy, {}};
                group.member_ids.reserve(local_group.size());
                // Sort by original image index for stable output order.
                std::sort(local_group.begin(), local_group.end(),
                    [&](std::size_t a, std::size_t b) {
                        return hashable_reps[a] < hashable_reps[b];
                    });
                for (const std::size_t local_idx : local_group) {
                    group.member_ids.push_back(images[hashable_reps[local_idx]].id);
                }
                result.push_back(std::move(group));
            }
        }
    }

    // ---------------------------------------------------------------
    // Phase 1b: Embedding-based "Related" grouping — near-linear candidate pass
    // Uses random-hyperplane LSH buckets to limit exact cosine checks.
    // ---------------------------------------------------------------
    {
        // Remember modified-copy component membership in O(n), so the related
        // pass can avoid returning the same relationship under a second label.
        std::unordered_map<std::string, std::size_t> modified_group_by_id;
        std::size_t modified_group_index = 0;
        for (const auto& group : result) {
            if (group.kind != DuplicateKind::ModifiedCopy) continue;
            for (const auto& member_id : group.member_ids) {
                modified_group_by_id[member_id] = modified_group_index;
            }
            ++modified_group_index;
        }

        // Collect reps that have embeddings.
        std::vector<std::size_t> embeddable_reps;
        embeddable_reps.reserve(representatives.size());
        for (const std::size_t rep : representatives) {
            if (!images[rep].embedding.empty()) {
                embeddable_reps.push_back(rep);
            }
        }

        if (embeddable_reps.size() >= 2) {
            DisjointSet dsu(embeddable_reps.size());
            EmbeddingLsh embedding_index(
                embedding_dimension,
                embeddable_reps.size());

            // Random-hyperplane LSH turns each embedding into short binary
            // signatures. We only run exact cosine checks on photos found in
            // the same or a neighbouring bucket. Candidate count is bounded,
            // so this pass stays near-linear instead of comparing all pairs.
            for (std::size_t i = 0; i < embeddable_reps.size(); ++i) {
                const auto& current = images[embeddable_reps[i]];
                const auto candidates = embedding_index.query_and_insert(
                    i,
                    current.embedding);
                for (const std::size_t candidate : candidates) {
                    const auto& earlier = images[embeddable_reps[candidate]];

                    const auto current_group = modified_group_by_id.find(current.id);
                    const auto earlier_group = modified_group_by_id.find(earlier.id);
                    if (current_group != modified_group_by_id.end() &&
                        earlier_group != modified_group_by_id.end() &&
                        current_group->second == earlier_group->second) {
                        continue;
                    }

                    if (!passes_metadata_filter(current, earlier)) continue;

                    if (stats != nullptr) {
                        ++stats->embedding_comparisons;
                    }
                    if (cosine_similarity(current.embedding, earlier.embedding)
                        >= options.similarity_threshold) {
                        dsu.unite(i, candidate);
                    }
                }
            }

            for (auto& local_group : dsu.groups(/*min_size=*/2)) {
                DuplicateGroup group{DuplicateKind::Related, {}};
                group.member_ids.reserve(local_group.size());
                std::sort(local_group.begin(), local_group.end(),
                    [&](std::size_t a, std::size_t b) {
                        return embeddable_reps[a] < embeddable_reps[b];
                    });
                for (const std::size_t local_idx : local_group) {
                    group.member_ids.push_back(images[embeddable_reps[local_idx]].id);
                }
                result.push_back(std::move(group));
            }
        }
    }

    // Singletons (when requested).
    if (options.include_singletons) {
        std::unordered_set<std::string> grouped_ids;
        for (const auto& group : result) {
            for (const auto& mid : group.member_ids) {
                grouped_ids.insert(mid);
            }
        }
        for (const auto& image : images) {
            if (grouped_ids.count(image.id) == 0) {
                // Emit one singleton group per visual category the image could
                // belong to, matching the original behaviour.
                if (image.has_perceptual_hash) {
                    result.push_back({
                        DuplicateKind::ModifiedCopy, {image.id}});
                }
                if (!image.embedding.empty()) {
                    result.push_back({
                        DuplicateKind::Related, {image.id}});
                }
                if (!image.content_hash.empty()) {
                    result.push_back({
                        DuplicateKind::Exact, {image.id}});
                }
                if (image.content_hash.empty() && !image.has_perceptual_hash && image.embedding.empty()) {
                    result.push_back({
                        DuplicateKind::Exact, {image.id}});
                }
            }
        }
    }

    return result;
}

}  // namespace edgegallery
