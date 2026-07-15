# EdgeGallery
Phone galleries accumulate exact duplicates, compressed copies, burst shots and unusable images. Existing cleanup tools can be opaque or cloud-dependent. EdgeGallery will scan selected media locally, identify likely duplicate/low-quality groups, explain its recommendation and allow the user to decide what to retain or delete.

## Current milestone

The native C++17 engine now includes the first tested algorithmic slice:

- exact grouping from a content hash;
- near-duplicate grouping from a 64-bit perceptual hash and configurable Hamming threshold;
- transitive clustering with Disjoint Set Union;
- deterministic group and member ordering for a stable UI contract;
- input validation, unit tests and CI.

The engine accepts precomputed fingerprints for now. Image decoding, SHA-256 and perceptual-hash extraction will be added as separate, reviewable changes.

## Build and test the native engine

Requirements: a C++17 compiler and CMake 3.16 or newer.

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
ctest --test-dir build --build-config Release --output-on-failure
```

For a quick local check without CMake:

```sh
g++ -std=c++17 -Wall -Wextra -Wpedantic \
  -Inative-engine/include \
  native-engine/src/duplicate_clusterer.cpp \
  native-engine/tests/duplicate_clusterer_test.cpp \
  -o edgegallery_engine_tests
./edgegallery_engine_tests
```

## Privacy and safety

EdgeGallery is designed for on-device analysis. Images are not uploaded, full file paths or image contents should not be logged, and media must never be deleted without an explicit platform confirmation from the user.
