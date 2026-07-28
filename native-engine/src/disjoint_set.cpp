#include "edgegallery/disjoint_set.hpp"

#include <stdexcept>
#include <unordered_map>

namespace edgegallery {

DisjointSet::DisjointSet(std::size_t n)
    : parent_(n), rank_(n, 0) {
    for (std::size_t i = 0; i < n; ++i) {
        parent_[i] = i;
    }
}

std::size_t DisjointSet::find(std::size_t x) {
    if (x >= parent_.size()) {
        throw std::out_of_range("DisjointSet::find index out of range");
    }
    // Path compression.
    while (parent_[x] != x) {
        parent_[x] = parent_[parent_[x]];  // path splitting (two-pass halving)
        x = parent_[x];
    }
    return x;
}

bool DisjointSet::unite(std::size_t x, std::size_t y) {
    x = find(x);
    y = find(y);
    if (x == y) return false;

    // Union by rank.
    if (rank_[x] < rank_[y]) {
        parent_[x] = y;
    } else if (rank_[x] > rank_[y]) {
        parent_[y] = x;
    } else {
        parent_[y] = x;
        ++rank_[x];
    }
    return true;
}

std::vector<std::vector<std::size_t>> DisjointSet::groups(std::size_t min_size) const {
    std::unordered_map<std::size_t, std::vector<std::size_t>> buckets;
    buckets.reserve(parent_.size());

    // We need a non-const find, but groups() is const.  Re-derive roots
    // without path compression to keep the method const.
    auto root_of = [&](std::size_t x) -> std::size_t {
        while (parent_[x] != x) {
            x = parent_[x];
        }
        return x;
    };

    for (std::size_t i = 0; i < parent_.size(); ++i) {
        buckets[root_of(i)].push_back(i);
    }

    std::vector<std::vector<std::size_t>> result;
    result.reserve(buckets.size());
    for (auto& [root, members] : buckets) {
        if (members.size() >= min_size) {
            result.push_back(std::move(members));
        }
    }
    return result;
}

}  // namespace edgegallery
