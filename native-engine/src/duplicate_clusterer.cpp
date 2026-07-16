#include "edgegallery/duplicate_clusterer.hpp"
using namespace std ; 
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
    explicit DisjointSet(size_t size) : parent_(size), rank_(size, 0) {
        iota(parent_.begin(), parent_.end(), 0);
    }

    size_t find(size_t item) {
        if (parent_[item] != item) {
            parent_[item] = find(parent_[item]);
        }
        return parent_[item];
    }

    void unite(size_t left, size_t right) {
        left = find(left);
        right = find(right);
        if (left == right) {
            return;
        }

        if (rank_[left] < rank_[right]) {
            swap(left, right);
        }
        parent_[right] = left;
        if (rank_[left] == rank_[right]) {
            ++rank_[left];
        }
    }

private:
    vector<std::size_t> parent_;
    vector<std::uint8_t> rank_;
};

bool all_content_hashes_match(
    const vector<ImageFingerprint>& images,
    const vector<std::size_t>& members) {
    const string& expected = images[members.front()].content_hash;
    if (expected.empty()) {
        return false;
    }

    return all_of(
        members.begin(), members.end(), [&](size_t index) {
            return images[index].content_hash == expected;
        });
}

}  // namespace

uint32_t hamming_distance(uint64_t left, uint64_t right) noexcept {
    uint64_t difference = left ^ right;
    uint32_t distance = 0;
    while (difference != 0) {
        difference &= difference - 1;
        ++distance;
    }
    return distance;
}

vector<DuplicateGroup> cluster_duplicates(
    const vector<ImageFingerprint>& images,
    const ClusterOptions& options) {
    if (options.hamming_threshold > 64) {
        throw invalid_argument("hamming_threshold must be between 0 and 64");
    }

    std::unordered_set<std::string> ids;
    ids.reserve(images.size());
    for (const auto& image : images) {
        if (image.id.empty()) {
            throw invalid_argument("image id must not be empty");
        }
        if (!ids.insert(image.id).second) {
            throw invalid_argument("image ids must be unique");
        }
    }

    DisjointSet groups(images.size());

    std::unordered_map<string, size_t> exact_hash_owner;
    exact_hash_owner.reserve(images.size());
    for (size_t index = 0; index < images.size(); ++index) {
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
    for (size_t left = 0; left < images.size(); ++left) {
        if (!images[left].has_perceptual_hash) {
            continue;
        }
        for (size_t right = left + 1; right < images.size(); ++right) {
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

    unordered_map<size_t, vector<std::size_t>> members_by_root;
    members_by_root.reserve(images.size());
    for (size_t index = 0; index < images.size(); ++index) {
        members_by_root[groups.find(index)].push_back(index);
    }

    vector<std::vector<std::size_t>> ordered_groups;
    ordered_groups.reserve(members_by_root.size());
    for (auto& entry : members_by_root) {
        auto& members = entry.second;
        if (options.include_singletons || members.size() > 1) {
            ordered_groups.push_back(std::move(members));
        }
    }
    sort(ordered_groups.begin(), ordered_groups.end(), [](const auto& left, const auto& right) {
        return left.front() < right.front();
    });

    vector<DuplicateGroup> result;
    result.reserve(ordered_groups.size());
    for (const auto& members : ordered_groups) {
        DuplicateGroup group;
        group.kind = all_content_hashes_match(images, members)
            ? DuplicateKind::Exact
            : DuplicateKind::VisuallySimilar;
        group.member_ids.reserve(members.size());
        for (size_t index : members) {
            group.member_ids.push_back(images[index].id);
        }
        result.push_back(move(group));
    }

    return result;
}

}  // namespace edgegallery
