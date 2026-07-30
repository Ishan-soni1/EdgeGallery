# ⚡ EdgeGallery: Ultra-Fast On-Device Duplicate & Similar Photo Intelligence

**EdgeGallery** is a privacy-first, high-performance Android engine designed for instant on-device image deduplication and semantic similarity grouping. By combining **SHA-256 byte hashing**, **64-bit perceptual dHash**, and **MobileNet V3 Small neural embeddings**, EdgeGallery detects exact duplicates, edited/resized copies, and visually related photos—completely offline and on-device.

> [!IMPORTANT]
> **Zero Cloud Dependency & Zero Privacy Leakage**: Photos, hashes, and feature vectors never leave your device. Only compact fingerprint primitives cross the Kotlin-to-C++ JNI boundary.

---

## ⚡ The Breakthrough: Defeating the $O(N^2)$ Complexity

### The Naive $O(N^2)$ Crisis
In a basic photo deduplication app, every photo is blindly compared with every other photo:
\[
\text{Total Comparisons} = \frac{N(N - 1)}{2} \approx O(N^2)
\]
* For **1,000 photos** $\rightarrow$ **499,500 comparisons**
* For **10,000 photos** $\rightarrow$ **49,995,000 comparisons** (CPU lockup, severe thermal throttling, and multi-minute freezes)

### The Near-Linear Candidate Architecture
EdgeGallery replaces global all-pairs comparison with a **4-phase candidate pipeline**:

```
                       +-------------------------------------------------------+
                       |              Input Photos (e.g., 10,000)              |
                       +-------------------------------------------------------+
                                                   |
                                                   v
   Phase 0             +-------------------------------------------------------+
   Byte-Exact          |          SHA-256 Exact Hash Bucketing [O(N)]          |
   Deduplication       +-------------------------------------------------------+
                                                   |
                                                   v (collapse exact duplicates to 1 representative per hash)
   Phase 1             +-------------------------------------------------------+
   Candidate Search    |   MIH for dHash + random-hyperplane LSH for vectors   |
                       |       [bounded local candidates, not every pair]       |
                       +-------------------------------------------------------+
                                                   |
                                                   v (candidate pairs only)
   Phase 2             +-------------------------------------------------------+
   Metadata            |    Aspect Ratio (20%) & File Size (10x) Pre-Filter    |
   Pre-Filtering       |        [Ejects ~90% false candidates instantly]       |
                       +-------------------------------------------------------+
                                                   |
                                                   v
   Phase 3             +-------------------------------------------------------+
   Graph Clustering &  |   Disjoint Set Union (DSU) Connected Components [O(N)]|
   UI Display          |       At most 20 evidence pairs shown per group       |
                       +-------------------------------------------------------+
```

---

## 🧠 The Tri-Layer Intelligence Stack

EdgeGallery categorizes photo relationships into three distinct tiers:

| Tier | Technique | Description | Target Use Case | Complexity |
| :--- | :--- | :--- | :--- | :--- |
| **1. Exact Duplicates** | **SHA-256** | Streaming 256-bit cryptographic digest over file bytes. | Identical file copies, exact downloads. | $O(N)$ Hash Table |
| **2. Modified Copies** | **64-bit dHash** | Perceptual difference hash computed from a $9 \times 8$ luminance thumbnail. Captures spatial brightness gradients. | Resized images, recompressed JPEGs, minor edits, color shifts. | $O(N \cdot m)$ via **MIH** |
| **3. Related Photos** | **MobileNet V3 Small + LSH** | On-device neural embeddings are placed in deterministic similarity buckets before exact cosine verification. | Different shots of the same scene, burst shots, semantic similarity. | Near-linear candidate pass; at most 256 cosine candidates per photo |

---

## 🛠️ Deep Dive into the Optimization Technologies

### 1. Multi-Index Hashing (MIH) for dHash Near-Duplicates
Perceptual 64-bit hashes are compared using **Hamming distance** (bit difference count). Scanning all pairs for $\text{distance} \le r$ is normally $O(N^2)$.

EdgeGallery implements **Multi-Index Hashing (MIH)** based on the **Pigeonhole Principle**:
* A 64-bit hash is split into $r + 1$ contiguous sub-blocks (e.g., for threshold $r = 8$, we split into 9 sub-blocks).
* If two 64-bit hashes differ by at most $r$ bits, **at least one sub-block must be 100% identical**.
* We build direct hash table lookups for each sub-block. Searching for candidates requires querying only matching sub-blocks, eliminating $99.9\%$ of irrelevant pairs!

### 2. Random-Hyperplane LSH for Neural Embeddings
The embedding stage uses a deliberately simple locality-sensitive hash:
* Each embedding is projected onto deterministic random hyperplanes.
* The positive/negative signs form short binary bucket keys.
* A query checks only its exact bucket and keys one bit away across 12 tables.
* Exact cosine similarity is calculated only for those candidates.
* Candidate work is capped at 256 earlier photos per image, preventing a dense bucket from becoming an all-pairs scan.

This is approximate search: it trades a small possibility of missing a related-photo match for predictable on-device performance. Exact byte and dHash duplicate detection remain deterministic.

### 3. Disjoint Set Union (DSU) Graph Clustering
Instead of heavy complete-link graph clustering ($O(N^3)$), near-duplicates are linked into connected components using **Disjoint Set Union (DSU)** with path compression and rank optimization:
* `find(x)`: $O(\alpha(N)) \approx O(1)$
* `unite(x, y)`: Instantly merges duplicate items into unified clusters.

### 4. Aspect Ratio & File Size Metadata Pre-Filtering
Before evaluating expensive floating-point cosine similarities or bitwise operations, candidate pairs must pass strict metadata sanity bounds:
* **Aspect Ratio Check**: $\frac{\text{AR}_{\text{left}}}{\text{AR}_{\text{right}}} \le 1.20$ (rejects pairings between landscape and portrait photos).
* **File Size Check**: $\max(\text{Size}_A, \text{Size}_B) \le 10 \times \min(\text{Size}_A, \text{Size}_B)$ (rejects pairing high-res originals with tiny thumbnails).

### 5. Bounded UI Evidence
The UI never rebuilds a global pair matrix. It displays at most 20 exact comparison records per result group, so a large group cannot reintroduce quadratic work after native clustering.

---

## 🗣️ Interview-Friendly Explanation

> “First I hash file bytes, so exact duplicates fall into the same hash-map bucket in one pass. For edited copies I use Multi-Index Hashing on a 64-bit perceptual hash. For neural embeddings I use Locality-Sensitive Hashing: similar vectors are likely to land in the same small buckets, and I run exact cosine similarity only on those candidates. Finally, Disjoint Set Union merges matching candidates into groups. The app therefore avoids comparing every photo with every other photo.”

For $N$ photos, the semantic pass performs at most $256N$ exact cosine checks instead of $\frac{N(N-1)}{2}$. Feature extraction and fixed-size index work are linear in the number of selected photos.

---

## 📂 Project Architecture

```text
EdgeGallery/
├── android-app/                              # Android Application (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       ├── cpp/                              # JNI Native Bridge
│       │   └── jni_bridge.cpp                # Native memory & primitive array unpacker
│       └── java/com/edgegallery/app/
│           ├── model/Models.kt               # ImageFeatures & DuplicateGroup data domain
│           ├── nativebridge/NativeEngine.kt  # JNI bindings to C++ engine
│           ├── processing/ImageProcessor.kt  # Multi-threaded SHA-256, dHash & MobileNet extraction
│           ├── processing/SimilarityMath.kt  # Intra-group UI similarity helpers
│           └── EdgeGalleryViewModel.kt       # Reactive UI state machine
│
├── native-engine/                            # Platform-Independent C++17 Clustering Library
│   ├── include/edgegallery/
│   │   ├── disjoint_set.hpp                  # Disjoint Set Union (DSU) header
│   │   ├── multi_index_hash.hpp              # Multi-Index Hashing (MIH) header
│   │   └── duplicate_clusterer.hpp           # High-level clustering API & ImageFingerprint
│   ├── src/
│   │   ├── disjoint_set.cpp                  # DSU implementation
│   │   ├── multi_index_hash.cpp              # MIH implementation
│   │   └── duplicate_clusterer.cpp           # 4-Phase pipeline engine
│   └── tests/                                # Native desktop unit tests
│       ├── disjoint_set_test.cpp
│       ├── multi_index_hash_test.cpp
│       └── duplicate_clusterer_test.cpp
│
└── CMakeLists.txt                            # Standalone C++ CMake test runner configuration
```

---

## 🛠️ Building & Running

### Prerequisites
* **Android Studio**: Ladybug / Jellyfish or newer
* **Android SDK**: API 36 (Android 15)
* **Android NDK**: `27.0.12077973`
* **CMake**: `3.22.1`
* **JDK**: 17 or 21

### 1. Build & Run the Android App
Open `android-app` in Android Studio, sync Gradle (`Ctrl + Shift + O`), and press **Run ▶️** (Shift + F10).

Or build via command line:
```powershell
cd android-app
.\gradlew.bat assembleDebug
```
The compiled APK will be located at:
`android-app/app/build/outputs/apk/debug/app-debug.apk`

### 2. Run Native Engine Tests (Desktop C++)
The native C++ core can be compiled and verified independently on Windows, Linux, or macOS without Android dependencies:

```bash
# Using CMake with MinGW / GCC / Clang / MSVC
cmake -B build_test -S . -DEDGEGALLERY_BUILD_TESTS=ON
cmake --build build_test

# Run unit test executables
./build_test/native-engine/edgegallery_mih_tests
./build_test/native-engine/edgegallery_dsu_tests
./build_test/native-engine/edgegallery_engine_tests
```

---

## 🔒 Privacy & Safety Guarantee

* **No Network Permissions**: EdgeGallery does not request or require internet access.
* **User-Controlled Deletion**: EdgeGallery never deletes automatically. The user must select photos and confirm a permanent-delete warning; unsupported storage providers fail safely.
* **On-Device Machine Learning**: MobileNet V3 runs entirely via LiteRT (TensorFlow Lite) in local device RAM.

---

## 📄 License
Designed & Developed for **EdgeGallery** — Privacy-first, high-performance edge compute image intelligence.
