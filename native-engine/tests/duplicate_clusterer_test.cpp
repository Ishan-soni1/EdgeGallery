#include "edgegallery/duplicate_clusterer.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
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
    expect(edgegallery::hamming_distance(0, 0xFULL) == 4, "changed bits are counted");
    expect(
        edgegallery::hamming_distance(0, ~std::uint64_t{0}) == 64,
        "opposite hashes have distance 64");
}

void test_cosine_similarity() {
    const std::vector<float> horizontal{1.0f, 0.0f};
    const std::vector<float> vertical{0.0f, 1.0f};
    expect(
        std::abs(edgegallery::cosine_similarity(horizontal, horizontal) - 1.0f) < 1e-6f,
        "identical vectors have similarity 1.0");
    expect(
        std::abs(edgegallery::cosine_similarity(horizontal, vertical)) < 1e-6f,
        "orthogonal vectors have similarity 0.0");
    expect(
        edgegallery::cosine_similarity({}, horizontal) == 0.0f,
        "empty vectors have similarity 0.0");
}

void test_exact_groups_remain_visible_with_a_semantic_neighbour() {
    const std::vector<ImageFingerprint> images{
        {"original", "same-sha", 0x0000, true, {1.0f, 0.0f}},
        {"copy", "same-sha", 0x0000, true, {1.0f, 0.0f}},
        {"similar", "other-sha", 0xFFFF, true, {0.95f, 0.05f}},
    };

    ClusterOptions options;
    options.hamming_threshold = 0;
    options.similarity_threshold = 0.90f;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 2, "exact and semantic relationships are both returned");
    expect(groups[0].kind == DuplicateKind::Exact, "the exact group keeps its label");
    expect(
        groups[0].member_ids == std::vector<std::string>({"original", "copy"}),
        "the exact group contains both byte-identical files");
    expect(groups[1].kind == DuplicateKind::VisuallySimilar, "semantic group is separate");
    expect(
        groups[1].member_ids == std::vector<std::string>({"original", "similar"}),
        "only one exact representative appears in the semantic group");
}

void test_dhash_finds_near_duplicates() {
    const std::vector<ImageFingerprint> images{
        {"original", "one", 0b0000, true, {1.0f, 0.0f}},
        {"resized", "two", 0b0011, true, {0.0f, 1.0f}},
        {"unrelated", "three", 0xFFFF, true, {0.0f, 0.0f}},
    };

    ClusterOptions options;
    options.hamming_threshold = 2;
    options.similarity_threshold = 0.99f;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 1, "dHash produces one near-duplicate group");
    expect(
        groups[0].member_ids == std::vector<std::string>({"original", "resized"}),
        "the resized image is grouped with its original");
}

void test_complete_link_prevents_similarity_chaining() {
    const std::vector<ImageFingerprint> images{
        {"first", "one", 0, false, {1.0f, 0.0f}},
        {"second", "two", 0, false, {0.9f, 0.4f}},
        {"third", "three", 0, false, {0.7f, 0.7f}},
        {"unrelated", "four", 0, false, {0.0f, 1.0f}},
    };

    ClusterOptions options;
    options.similarity_threshold = 0.80f;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 1, "only a mutually similar group is returned");
    expect(
        groups[0].member_ids == std::vector<std::string>({"first", "second"}),
        "a transitive third image is not pulled into the group");
}

void test_missing_features_and_singletons() {
    const std::vector<ImageFingerprint> images{
        {"unknown", "", 0, false, {}},
        {"known", "known-hash", 42, true, {1.0f, 0.0f}},
    };

    expect(edgegallery::cluster_duplicates(images).empty(), "singletons are hidden by default");

    ClusterOptions options;
    options.include_singletons = true;
    const auto groups = edgegallery::cluster_duplicates(images, options);
    expect(groups.size() == 2, "representative singletons can be requested");
    expect(groups[0].member_ids[0] == "unknown", "singleton order is deterministic");
}

void test_validation() {
    expect_invalid_argument(
        [] { edgegallery::cluster_duplicates({{"", "hash", 0, true, {1.0f}}}); },
        "empty ids are rejected");
    expect_invalid_argument(
        [] {
            edgegallery::cluster_duplicates({
                {"same", "a", 0, true, {1.0f}},
                {"same", "b", 1, true, {0.0f}},
            });
        },
        "duplicate ids are rejected");
    expect_invalid_argument(
        [] {
            ClusterOptions options;
            options.hamming_threshold = 65;
            edgegallery::cluster_duplicates({}, options);
        },
        "invalid Hamming thresholds are rejected");
    expect_invalid_argument(
        [] {
            ClusterOptions options;
            options.similarity_threshold = -0.1f;
            edgegallery::cluster_duplicates({}, options);
        },
        "negative similarity thresholds are rejected");
    expect_invalid_argument(
        [] {
            ClusterOptions options;
            options.similarity_threshold = std::numeric_limits<float>::quiet_NaN();
            edgegallery::cluster_duplicates({}, options);
        },
        "NaN similarity thresholds are rejected");
    expect_invalid_argument(
        [] {
            edgegallery::cluster_duplicates({
                {"one", "a", 0, true, {1.0f}},
                {"two", "b", 1, true, {1.0f, 0.0f}},
            });
        },
        "mismatched embedding dimensions are rejected");
}

}  // namespace

int main() {
    test_hamming_distance();
    test_cosine_similarity();
    test_exact_groups_remain_visible_with_a_semantic_neighbour();
    test_dhash_finds_near_duplicates();
    test_complete_link_prevents_similarity_chaining();
    test_missing_features_and_singletons();
    test_validation();

    if (failures != 0) {
        std::cerr << failures << " test(s) failed\n";
        return EXIT_FAILURE;
    }

    std::cout << "All duplicate-clustering tests passed\n";
    return EXIT_SUCCESS;
}
