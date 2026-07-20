#include "edgegallery/duplicate_clusterer.hpp"

#include <cmath>
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

void test_cosine_similarity() {
    // Identical unit vectors have similarity 1.0.
    std::vector<float> a = {1.0f, 0.0f, 0.0f};
    float sim = edgegallery::cosine_similarity(a, a);
    expect(std::abs(sim - 1.0f) < 1e-6f, "identical vectors have similarity 1.0");

    // Orthogonal vectors have similarity 0.0.
    std::vector<float> b = {0.0f, 1.0f, 0.0f};
    sim = edgegallery::cosine_similarity(a, b);
    expect(std::abs(sim) < 1e-6f, "orthogonal vectors have similarity 0.0");

    // Opposite vectors have similarity -1.0.
    std::vector<float> c = {-1.0f, 0.0f, 0.0f};
    sim = edgegallery::cosine_similarity(a, c);
    expect(std::abs(sim + 1.0f) < 1e-6f, "opposite vectors have similarity -1.0");

    // Empty vectors return 0.0.
    std::vector<float> empty;
    sim = edgegallery::cosine_similarity(empty, a);
    expect(sim == 0.0f, "empty vector returns similarity 0.0");

    // Mismatched sizes return 0.0.
    std::vector<float> short_vec = {1.0f, 2.0f};
    sim = edgegallery::cosine_similarity(a, short_vec);
    expect(sim == 0.0f, "mismatched sizes return similarity 0.0");
}

void test_exact_duplicates() {
    const std::vector<ImageFingerprint> images{
        {"a", "sha-a", {1.0f, 0.0f, 0.0f}},
        {"b", "sha-a", {0.0f, 1.0f, 0.0f}},
        {"c", "sha-c", {0.0f, 0.0f, 1.0f}},
    };

    ClusterOptions options;
    options.similarity_threshold = 0.99f;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 1, "one exact duplicate group is returned");
    expect(groups[0].kind == DuplicateKind::Exact, "exact group is labelled correctly");
    expect(groups[0].member_ids == std::vector<std::string>({"a", "b"}), "exact members are stable");
}

void test_visual_similarity_and_transitive_grouping() {
    // Embeddings chosen so that adjacent pairs have high cosine similarity
    // but the first and third are only transitively connected.
    const std::vector<ImageFingerprint> images{
        {"first",     "one",   {1.0f, 0.0f, 0.0f, 0.0f}},
        {"second",    "two",   {0.9f, 0.4f, 0.0f, 0.0f}},
        {"third",     "three", {0.7f, 0.7f, 0.0f, 0.0f}},
        {"unrelated", "four",  {0.0f, 0.0f, 0.0f, 1.0f}},
    };

    ClusterOptions options;
    options.similarity_threshold = 0.80f;
    const auto groups = edgegallery::cluster_duplicates(images, options);

    expect(groups.size() == 1, "transitive visual matches form one group");
    expect(groups[0].kind == DuplicateKind::VisuallySimilar, "visual group is labelled correctly");
    expect(
        groups[0].member_ids == std::vector<std::string>({"first", "second", "third"}),
        "DSU preserves input order inside a group");
}

void test_missing_features_and_singletons() {
    const std::vector<ImageFingerprint> images{
        {"unknown", "",           {}},
        {"known",   "known-hash", {1.0f, 0.0f}},
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
        [] { edgegallery::cluster_duplicates({{""  , "hash", {1.0f}}}); },
        "empty ids are rejected");
    expect_invalid_argument(
        [] { edgegallery::cluster_duplicates({{"same", "a", {1.0f}}, {"same", "b", {0.0f}}}); },
        "duplicate ids are rejected");
    expect_invalid_argument(
        [] {
            ClusterOptions options;
            options.similarity_threshold = 1.5f;
            edgegallery::cluster_duplicates({}, options);
        },
        "invalid similarity thresholds are rejected");
    expect_invalid_argument(
        [] {
            ClusterOptions options;
            options.similarity_threshold = -0.1f;
            edgegallery::cluster_duplicates({}, options);
        },
        "negative similarity thresholds are rejected");
}

}  // namespace

int main() {
    test_cosine_similarity();
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
