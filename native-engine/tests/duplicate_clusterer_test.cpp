#include "edgegallery/duplicate_clusterer.hpp"

#include <cstdlib>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using edgegallery::ClusterOptions;
using edgegallery::DuplicateKind;
using edgegallery::ImageFingerprint;

int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

template <typename Action>
void expect_invalid_argument(Action action, const std::string& message) {
    try {
        action();
        expect(false, message);
    } catch (const std::invalid_argument&) {
        // Expected.
    } catch (...) {
        expect(false, message + " (wrong exception type)");
    }
}

void test_hamming_distance() {
    expect(edgegallery::hamming_distance(0, 0) == 0, "equal hashes have distance zero");
    expect(edgegallery::hamming_distance(0, 0xFULL) == 4, "four changed bits are counted");
    expect(
        edgegallery::hamming_distance(0, ~std::uint64_t{0}) == 64,
        "opposite hashes have distance 64");
}

void test_exact_duplicates() {
    const std::vector<ImageFingerprint> images{
        {"a", "sha-a", 0x0000, true},
        {"b", "sha-a", 0xFFFF, true},
        {"c", "sha-c", 0x1234, true},
    };

    ClusterOptions options;
    options.hamming_threshold = 0;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 1, "one exact duplicate group is returned");
    expect(groups[0].kind == DuplicateKind::Exact, "exact group is labelled correctly");
    expect(groups[0].member_ids == std::vector<std::string>({"a", "b"}), "exact members are stable");
}

void test_visual_similarity_and_transitive_grouping() {
    const std::vector<ImageFingerprint> images{
        {"first", "one", 0b0000, true},
        {"second", "two", 0b0011, true},
        {"third", "three", 0b1111, true},
        {"unrelated", "four", 0xFFFF, true},
    };

    ClusterOptions options;
    options.hamming_threshold = 2;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 1, "transitive visual matches form one group");
    expect(groups[0].kind == DuplicateKind::VisuallySimilar, "visual group is labelled correctly");
    expect(
        groups[0].member_ids == std::vector<std::string>({"first", "second", "third"}),
        "DSU preserves input order inside a group");
}

void test_missing_features_and_singletons() {
    const std::vector<ImageFingerprint> images{
        {"unknown", "", 0, false},
        {"known", "known-hash", 42, true},
    };

    expect(edgegallery::cluster_duplicates(images).empty(), "singletons are hidden by default");

    ClusterOptions options;
    options.include_singletons = true;
    const auto groups = edgegallery::cluster_duplicates(images, options);
    expect(groups.size() == 2, "singletons can be requested");
    expect(groups[0].member_ids[0] == "unknown", "singleton output is deterministic");
}

void test_validation() {
    expect_invalid_argument(
        [] { edgegallery::cluster_duplicates({{"", "hash", 0, true}}); },
        "empty ids are rejected");
    expect_invalid_argument(
        [] { edgegallery::cluster_duplicates({{"same", "a", 0, true}, {"same", "b", 1, true}}); },
        "duplicate ids are rejected");
    expect_invalid_argument(
        [] {
            ClusterOptions options;
            options.hamming_threshold = 65;
            edgegallery::cluster_duplicates({}, options);
        },
        "invalid Hamming thresholds are rejected");
}

}  // namespace

int main() {
    test_hamming_distance();
    test_exact_duplicates();
    test_visual_similarity_and_transitive_grouping();
    test_missing_features_and_singletons();
    test_validation();

    if (failures != 0) {
        std::cerr << failures << " test(s) failed\n";
        return EXIT_FAILURE;
    }

    std::cout << "All duplicate-clustering tests passed\n";
    return EXIT_SUCCESS;
}
