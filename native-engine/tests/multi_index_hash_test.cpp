#include "edgegallery/multi_index_hash.hpp"

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

namespace {

using edgegallery::MultiIndexHash;

int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

void test_exact_match_is_returned() {
    MultiIndexHash mih(8);
    mih.insert(0, 0xAAAAAAAAAAAAAAAAULL);
    const auto results = mih.query(0xAAAAAAAAAAAAAAAAULL);
    expect(results.size() == 1 && results[0] == 0,
        "exact match returns the inserted index");
}

void test_within_threshold_is_found() {
    MultiIndexHash mih(4);
    // Hash differs in exactly 3 bits from the query.
    mih.insert(0, 0b0000);
    mih.insert(1, 0b0111);
    mih.insert(2, 0xFFFFFFFFFFFFFFFFULL);  // far away
    const auto results = mih.query(0b0000);
    bool found_0 = false, found_1 = false, found_2 = false;
    for (auto idx : results) {
        if (idx == 0) found_0 = true;
        if (idx == 1) found_1 = true;
        if (idx == 2) found_2 = true;
    }
    expect(found_0, "self-match is found");
    expect(found_1, "hash within threshold is found");
    expect(!found_2, "hash beyond threshold is not found");
}

void test_beyond_threshold_is_excluded() {
    MultiIndexHash mih(2);
    mih.insert(0, 0b0000);
    mih.insert(1, 0b0111);  // 3 bits differ, beyond threshold of 2
    const auto results = mih.query(0b0000);
    bool found_1 = false;
    for (auto idx : results) {
        if (idx == 1) found_1 = true;
    }
    expect(!found_1, "hash beyond threshold is excluded");
}

void test_boundary_threshold() {
    MultiIndexHash mih(3);
    mih.insert(0, 0b0000);
    mih.insert(1, 0b0111);  // exactly 3 bits, should be included at threshold 3
    const auto results = mih.query(0b0000);
    bool found_1 = false;
    for (auto idx : results) {
        if (idx == 1) found_1 = true;
    }
    expect(found_1, "hash at exact threshold boundary is included");
}

void test_empty_index_returns_empty() {
    MultiIndexHash mih(8);
    const auto results = mih.query(42);
    expect(results.empty(), "empty index returns no results");
}

void test_many_insertions() {
    MultiIndexHash mih(8);
    for (std::size_t i = 0; i < 1000; ++i) {
        mih.insert(i, static_cast<std::uint64_t>(i * 7919));
    }
    // Just ensure it doesn't crash and returns something.
    const auto results = mih.query(0);
    expect(!results.empty(), "at least the self-match is returned");
}

}  // namespace

int main() {
    test_exact_match_is_returned();
    test_within_threshold_is_found();
    test_beyond_threshold_is_excluded();
    test_boundary_threshold();
    test_empty_index_returns_empty();
    test_many_insertions();

    if (failures != 0) {
        std::cerr << failures << " MIH test(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "All MIH tests passed\n";
    return EXIT_SUCCESS;
}
