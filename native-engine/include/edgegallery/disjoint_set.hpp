#pragma once

#include <cstddef>
#include <vector>

namespace edgegallery {

/**
 * Disjoint Set Union (Union-Find) with union-by-rank and path compression.
 *
 * Each merge is O(α(n)) amortised, where α is the inverse Ackermann function
 * and grows so slowly that it is effectively O(1) for all practical sizes.
 */
class DisjointSet {
public:
    explicit DisjointSet(std::size_t n);

    /// Returns the canonical representative for the set containing @p x.
    std::size_t find(std::size_t x);

    /// Merges the sets containing @p x and @p y.  Returns true if they were
    /// previously in different sets.
    bool unite(std::size_t x, std::size_t y);

    /// Collects all groups that have at least @p min_size members.
    /// Each inner vector contains the original indices belonging to that group.
    std::vector<std::vector<std::size_t>> groups(std::size_t min_size = 2) const;

private:
    std::vector<std::size_t> parent_;
    std::vector<std::size_t> rank_;
};

}  // namespace edgegallery
