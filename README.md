# EdgeGallery

EdgeGallery is a readable, privacy-first Android MVP that analyses photos chosen
through the system Photo Picker. It reports exact duplicates, visually similar
groups and simple exposure warnings. It does not upload, recommend or delete
photos.

## What works

- Kotlin/Compose Android application with select, scan, progress and results screens.
- Streaming SHA-256 for byte-identical files.
- 64-bit dHash calculated from a 9x8 grayscale thumbnail.
- Bundled MobileNet V3 Small feature-vector model for on-device semantic similarity.
- Simple underexposure/overexposure warnings from a 64x64 thumbnail.
- JNI bridge from Kotlin to the C++17 engine.
- Brute-force dHash and cosine comparisons with complete-link grouping in C++.
- Native, Kotlin and on-device model tests plus GitHub Actions workflows.

## Data flow

```text
Photo Picker -> ImageProcessor -> SHA-256/dHash/exposure/MobileNet embedding
             -> JNI -> C++ exact and complete-link visual grouping
             -> ViewModel -> Compose results
```

Kotlin handles Android file access and feature extraction. Only compact hashes
and embeddings cross JNI. The C++ engine never receives full image data, and no
photo or derived feature leaves the device.

## Project structure

```text
android-app/       Kotlin, Compose, Android resources and JNI adapter
native-engine/     Platform-independent C++ clustering library and tests
data/              Local test-data instructions (actual images are ignored)
.github/workflows/ Native and Android continuous integration
```

## Build the Android app

Install Android Studio with Android SDK 36, NDK `27.0.12077973`, CMake `3.22.1`
and JDK 17. Then open `android-app` in Android Studio or run:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is created under:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

## Test the native engine without Android

```sh
g++ -std=c++17 -Wall -Wextra -Wpedantic -Werror \
  -Inative-engine/include \
  native-engine/src/duplicate_clusterer.cpp \
  native-engine/tests/duplicate_clusterer_test.cpp \
  -o edgegallery_engine_tests
./edgegallery_engine_tests
```

## Manual MVP test

Select an original image, an exact copy, a resized/recompressed copy, an
unrelated image, two different photos of the same subject, a dark image and a
bright image. Confirm that the app shows:

- the original and exact copy in an exact group;
- the resized/recompressed copy in a visually similar group when its dHash is
  within the configured threshold;
- the two photos of the same subject in a visually similar group when their
  MobileNet cosine similarity is above the configured threshold;
- the dark and bright images under exposure warnings;
- no unrelated image in a duplicate group.

## Known limits

- At most 100 photos can be selected in one scan.
- Scans are sequential and are not persisted.
- dHash can struggle with rotation, large crops, mirroring and major edits;
  MobileNet complements it with semantic similarity but is not duplicate proof.
- Exposure thresholds are simple MVP heuristics, not photographic judgement.
- Brute-force similarity search is O(n^2) and has not yet been benchmarked.
- Similarity thresholds still need calibration against a labelled photo set.
