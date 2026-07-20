#include "edgegallery/duplicate_clusterer.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace edgegallery {
namespace {

template <typename Matches>
std::vector<std::vector<std::size_t>> build_complete_link_groups(
    const std::vector<std::size_t>& candidates,
    Matches matches) {
    std::vector<std::vector<std::size_t>> groups;

    for (const std::size_t candidate : candidates) {
        bool added = false;
        for (auto& group : groups) {
            const bool matches_every_member = std::all_of(
                group.begin(), group.end(), [&](std::size_t member) {
                    return matches(candidate, member);
                });
            if (matches_every_member) {
                group.push_back(candidate);
                added = true;
                break;
            }
        }

        if (!added) {
            groups.push_back({candidate});
        }
    }

    return groups;
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
    const ClusterOptions& options) {
    if (options.hamming_threshold > 64) {
        throw std::invalid_argument("hamming_threshold must be between 0 and 64");
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

    // Exact groups are produced independently, so a semantic neighbour can
    // never relabel or hide a byte-identical SHA-256 group.
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

    // Compare one representative for each exact hash. Exact copies are already
    // reported above and should not inflate a visually-similar group.
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

    const auto visually_matches = [&](std::size_t left, std::size_t right) {
        const bool near_duplicate =
            images[left].has_perceptual_hash &&
            images[right].has_perceptual_hash &&
            hamming_distance(
                images[left].perceptual_hash,
                images[right].perceptual_hash) <= options.hamming_threshold;

        const bool semantic_match =
            !images[left].embedding.empty() &&
            !images[right].embedding.empty() &&
            cosine_similarity(
                images[left].embedding,
                images[right].embedding) >= options.similarity_threshold;

        return near_duplicate || semantic_match;
    };

    // Complete-link grouping requires a new image to match every existing
    // member. This prevents A~B and B~C from grouping A with an unrelated C.
    const auto visual_groups = build_complete_link_groups(representatives, visually_matches);
    for (const auto& members : visual_groups) {
        if (!options.include_singletons && members.size() < 2) {
            continue;
        }
        DuplicateGroup group{DuplicateKind::VisuallySimilar, {}};
        group.member_ids.reserve(members.size());
        for (const std::size_t index : members) {
            group.member_ids.push_back(images[index].id);
        }
        result.push_back(std::move(group));
    }

    return result;
}

}  // namespace edgegallery
