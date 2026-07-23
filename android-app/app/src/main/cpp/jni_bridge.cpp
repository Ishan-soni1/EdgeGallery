#include <jni.h>

#include "edgegallery/duplicate_clusterer.hpp"

#include <cstdint>
#include <exception>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr jint kExactGroup = 0;
constexpr jint kModifiedCopyGroup = 1;
constexpr jint kRelatedGroup = 2;

void throw_java_exception(JNIEnv* environment, const char* class_name, const char* message) {
    jclass exception_class = environment->FindClass(class_name);
    if (exception_class != nullptr) {
        environment->ThrowNew(exception_class, message);
    }
}

std::vector<std::string> read_strings(JNIEnv* environment, jobjectArray java_strings) {
    const jsize count = environment->GetArrayLength(java_strings);
    std::vector<std::string> strings;
    strings.reserve(static_cast<std::size_t>(count));

    for (jsize index = 0; index < count; ++index) {
        auto java_string = static_cast<jstring>(environment->GetObjectArrayElement(java_strings, index));
        if (java_string == nullptr) {
            throw std::invalid_argument("content hashes must not contain null values");
        }

        const char* utf8 = environment->GetStringUTFChars(java_string, nullptr);
        if (utf8 == nullptr) {
            environment->DeleteLocalRef(java_string);
            throw std::runtime_error("could not read a content hash");
        }

        strings.emplace_back(utf8);
        environment->ReleaseStringUTFChars(java_string, utf8);
        environment->DeleteLocalRef(java_string);
    }

    return strings;
}

std::vector<std::uint64_t> read_hashes(JNIEnv* environment, jlongArray java_hashes) {
    const jsize count = environment->GetArrayLength(java_hashes);
    std::vector<jlong> signed_hashes(static_cast<std::size_t>(count));
    environment->GetLongArrayRegion(java_hashes, 0, count, signed_hashes.data());

    std::vector<std::uint64_t> hashes;
    hashes.reserve(static_cast<std::size_t>(count));
    for (const jlong hash : signed_hashes) {
        hashes.push_back(static_cast<std::uint64_t>(hash));
    }
    return hashes;
}

std::vector<edgegallery::ImageFingerprint> make_fingerprints(
    const std::vector<std::string>& content_hashes,
    const std::vector<std::uint64_t>& difference_hashes,
    const float* embeddings,
    int embedding_dimension,
    int image_count) {
    if (static_cast<int>(content_hashes.size()) != image_count) {
        throw std::invalid_argument("content hash count must match image count");
    }
    if (static_cast<int>(difference_hashes.size()) != image_count) {
        throw std::invalid_argument("dHash count must match image count");
    }

    std::vector<edgegallery::ImageFingerprint> fingerprints;
    fingerprints.reserve(static_cast<std::size_t>(image_count));

    for (int i = 0; i < image_count; ++i) {
        const float* start = embeddings + static_cast<std::ptrdiff_t>(i) * embedding_dimension;
        fingerprints.push_back({
            std::to_string(i),
            content_hashes[static_cast<std::size_t>(i)],
            difference_hashes[static_cast<std::size_t>(i)],
            true,
            std::vector<float>(start, start + embedding_dimension),
        });
    }

    return fingerprints;
}

/**
 * Encodes every group as:
 * [group type, member count, member index, member index, ...]
 *
 * Using indices keeps the JNI boundary small and avoids constructing Kotlin
 * objects from C++. NativeEngine.kt converts this array into readable models.
 */
std::vector<jint> encode_groups(const std::vector<edgegallery::DuplicateGroup>& groups) {
    std::vector<jint> encoded;

    for (const auto& group : groups) {
        jint type = kExactGroup;
        switch (group.kind) {
            case edgegallery::DuplicateKind::Exact:
                type = kExactGroup;
                break;
            case edgegallery::DuplicateKind::ModifiedCopy:
                type = kModifiedCopyGroup;
                break;
            case edgegallery::DuplicateKind::Related:
                type = kRelatedGroup;
                break;
        }

        encoded.push_back(type);
        encoded.push_back(static_cast<jint>(group.member_ids.size()));
        for (const auto& member_id : group.member_ids) {
            encoded.push_back(static_cast<jint>(std::stoul(member_id)));
        }
    }

    return encoded;
}

jintArray make_java_int_array(JNIEnv* environment, const std::vector<jint>& values) {
    auto result = environment->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        environment->SetIntArrayRegion(
            result,
            0,
            static_cast<jsize>(values.size()),
            values.data());
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_edgegallery_app_nativebridge_NativeEngine_clusterNative(
    JNIEnv* environment,
    jobject,
    jobjectArray java_content_hashes,
    jlongArray java_difference_hashes,
    jfloatArray java_embeddings,
    jint java_embedding_dimension,
    jint java_image_count,
    jint java_hamming_threshold,
    jfloat java_similarity_threshold) {
    try {
        if (java_embedding_dimension <= 0) {
            throw std::invalid_argument("Embedding dimension must be positive");
        }
        if (java_image_count <= 0) {
            throw std::invalid_argument("Image count must be positive");
        }
        if (java_hamming_threshold < 0 || java_hamming_threshold > 64) {
            throw std::invalid_argument("Hamming threshold must be between 0 and 64");
        }
        if (java_similarity_threshold < 0.0f || java_similarity_threshold > 1.0f) {
            throw std::invalid_argument("Similarity threshold must be between 0.0 and 1.0");
        }

        const auto content_hashes = read_strings(environment, java_content_hashes);
        const auto difference_hashes = read_hashes(environment, java_difference_hashes);
        const auto expected_embedding_count =
            static_cast<long long>(java_embedding_dimension) * java_image_count;
        if (expected_embedding_count > std::numeric_limits<jsize>::max() ||
            environment->GetArrayLength(java_embeddings) != expected_embedding_count) {
            throw std::invalid_argument("Embedding array size must match image count and dimension");
        }

        // Pin the float array for zero-copy access across the JNI boundary.
        jfloat* embeddings_ptr = environment->GetFloatArrayElements(java_embeddings, nullptr);
        if (embeddings_ptr == nullptr) {
            throw std::runtime_error("could not access embedding array");
        }

        std::vector<edgegallery::ImageFingerprint> fingerprints;
        try {
            fingerprints = make_fingerprints(
                content_hashes,
                difference_hashes,
                embeddings_ptr,
                static_cast<int>(java_embedding_dimension),
                static_cast<int>(java_image_count));
        } catch (...) {
            environment->ReleaseFloatArrayElements(java_embeddings, embeddings_ptr, JNI_ABORT);
            throw;
        }
        environment->ReleaseFloatArrayElements(java_embeddings, embeddings_ptr, JNI_ABORT);

        edgegallery::ClusterOptions options;
        options.hamming_threshold = static_cast<std::uint32_t>(java_hamming_threshold);
        options.similarity_threshold = java_similarity_threshold;
        const auto groups = edgegallery::cluster_duplicates(fingerprints, options);

        return make_java_int_array(environment, encode_groups(groups));
    } catch (const std::invalid_argument& error) {
        throw_java_exception(environment, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception& error) {
        throw_java_exception(environment, "java/lang/RuntimeException", error.what());
    }

    return nullptr;
}
