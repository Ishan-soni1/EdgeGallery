#include "edgegallery/disjoint_set.hpp"

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

using edgegallery::DisjointSet;

int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

void test_initial_state() {
    DisjointSet dsu(5);
    for (std::size_t i = 0; i < 5; ++i) {
        expect(dsu.find(i) == i, "each element is its own root initially");
    }
}

void test_unite_and_find() {
    DisjointSet dsu(5);
    expect(dsu.unite(0, 1), "first unite returns true");
    expect(dsu.find(0) == dsu.find(1), "united elements share a root");
    expect(!dsu.unite(0, 1), "redundant unite returns false");
}

void test_transitive_union() {
    DisjointSet dsu(5);
    dsu.unite(0, 1);
    dsu.unite(1, 2);
    expect(dsu.find(0) == dsu.find(2), "transitive union works");
}

void test_groups_min_size() {
    DisjointSet dsu(5);
    dsu.unite(0, 1);
    dsu.unite(2, 3);
    // Element 4 is alone.
    auto groups = dsu.groups(2);
    expect(groups.size() == 2, "two groups of size >= 2");

    auto all_groups = dsu.groups(1);
    expect(all_groups.size() == 3, "three groups when singletons included");
}

void test_single_element() {
    DisjointSet dsu(1);
    expect(dsu.find(0) == 0, "single element is its own root");
    auto groups = dsu.groups(1);
    expect(groups.size() == 1, "single element forms one group");
}

}  // namespace

int main() {
    test_initial_state();
    test_unite_and_find();
    test_transitive_union();
    test_groups_min_size();
    test_single_element();

    if (failures != 0) {
        std::cerr << failures << " DSU test(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "All DSU tests passed\n";
    return EXIT_SUCCESS;
}
