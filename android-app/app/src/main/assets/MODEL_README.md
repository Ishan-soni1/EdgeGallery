# MobileNet V3 Small — Feature Vector (TFLite)

This directory must contain the TFLite model file used by EdgeGallery for
on-device semantic-similarity detection via deep-learning embeddings. SHA-256
and dHash remain responsible for exact and near-duplicate evidence.

## Required file

    mobilenet_v3_small_feature_vector.tflite

## How to obtain it

1. Visit TensorFlow Hub:
   https://tfhub.dev/google/lite-model/imagenet/mobilenet_v3_small_100_224/feature_vector/5/metadata/1

2. Click **Download** to get the `.tflite` file.

3. Rename it (if necessary) to `mobilenet_v3_small_feature_vector.tflite`.

4. Place it in this directory:
   `android-app/app/src/main/assets/mobilenet_v3_small_feature_vector.tflite`

## Model details

| Property           | Value                |
|--------------------|----------------------|
| Input shape        | `[1, 224, 224, 3]`   |
| Input range        | `[0.0, 1.0]`         |
| Output shape       | `[1, 1024]`          |
| Output type        | `float32`            |
| File size          | ~6 MB                |
| Architecture       | MobileNet V3 Small   |
| Task               | Feature vector       |

The model runs entirely on-device. No internet connection is required.
