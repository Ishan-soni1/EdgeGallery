#include "edgegallery/duplicate_clusterer.hpp"

#include <algorithm>
#include <numeric>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace edgegallery {
namespace {

class DisjointSet {
public:
    explicit DisjointSet(std::size_t size) : parent_(size), rank_(size, 0) {
        std::iota(parent_.begin(), parent_.end(), 0);
    }

    std::size_t find(std::size_t item) {
        if (parent_[item] != item) {
            parent_[item] = find(parent_[item]);
        }
        return parent_[item];
    }

    void unite(std::size_t left, std::size_t right) {
        left = find(left);
        right = find(right);
        if (left == right) {
            return;
        }

        if (rank_[left] < rank_[right]) {
            std::swap(left, right);
        }
        parent_[right] = left;
        if (rank_[left] == rank_[right]) {
            ++rank_[left];
        }
    }

private:
    std::vector<std::size_t> parent_;
    std::vector<std::uint8_t> rank_;
};

bool all_content_hashes_match(
    const std::vector<ImageFingerprint>& images,
    const std::vector<std::size_t>& members) {
    const std::string& expected = images[members.front()].content_hash;
    if (expected.empty()) {
        return false;
    }

    return std::all_of(
        members.begin(), members.end(), [&](std::size_t index) {
            return images[index].content_hash == expected;
        });
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

std::vector<DuplicateGroup> cluster_duplicates(
    const std::vector<ImageFingerprint>& images,
    const ClusterOptions& options) {
    if (options.hamming_threshold > 64) {
        throw std::invalid_argument("hamming_threshold must be between 0 and 64");
    }

    std::unordered_set<std::string> ids;
    ids.reserve(images.size());
    for (const auto& image : images) {
        if (image.id.empty()) {
            throw std::invalid_argument("image id must not be empty");
        }
        if (!ids.insert(image.id).second) {
            throw std::invalid_argument("image ids must be unique");
        }
    }

    DisjointSet groups(images.size());

    std::unordered_map<std::string, std::size_t> exact_hash_owner;
    exact_hash_owner.reserve(images.size());
    for (std::size_t index = 0; index < images.size(); ++index) {
        const auto& content_hash = images[index].content_hash;
        if (content_hash.empty()) {
            continue;
        }

        const auto insertion = exact_hash_owner.emplace(content_hash, index);
        if (!insertion.second) {
            groups.unite(index, insertion.first->second);
        }
    }

    // This intentionally starts with a clear O(n^2) baseline. A metric index
    // should replace it only after profiling shows a meaningful benefit.
    for (std::size_t left = 0; left < images.size(); ++left) {
        if (!images[left].has_perceptual_hash) {
            continue;
        }
        for (std::size_t right = left + 1; right < images.size(); ++right) {
            if (!images[right].has_perceptual_hash) {
                continue;
            }
            if (hamming_distance(
                    images[left].perceptual_hash,
                    images[right].perceptual_hash) <= options.hamming_threshold) {
                groups.unite(left, right);
            }
        }
    }

    std::unordered_map<std::size_t, std::vector<std::size_t>> members_by_root;
    members_by_root.reserve(images.size());
    for (std::size_t index = 0; index < images.size(); ++index) {
        members_by_root[groups.find(index)].push_back(index);
    }

    std::vector<std::vector<std::size_t>> ordered_groups;
    ordered_groups.reserve(members_by_root.size());
    for (auto& entry : members_by_root) {
        auto& members = entry.second;
        if (options.include_singletons || members.size() > 1) {
            ordered_groups.push_back(std::move(members));
        }
    }
    std::sort(ordered_groups.begin(), ordered_groups.end(), [](const auto& left, const auto& right) {
        return left.front() < right.front();
    });

    std::vector<DuplicateGroup> result;
    result.reserve(ordered_groups.size());
    for (const auto& members : ordered_groups) {
        DuplicateGroup group;
        group.kind = all_content_hashes_match(images, members)
            ? DuplicateKind::Exact
            : DuplicateKind::VisuallySimilar;
        group.member_ids.reserve(members.size());
        for (std::size_t index : members) {
            group.member_ids.push_back(images[index].id);
        }
        result.push_back(std::move(group));
    }

    return result;
}

}  // namespace edgegallery
