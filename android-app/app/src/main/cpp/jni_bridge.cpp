#include <jni.h>

#include "edgegallery/duplicate_clusterer.hpp"

#include <cstdint>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr jint kExactGroup = 0;
constexpr jint kVisuallySimilarGroup = 1;

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
    for (jlong hash : signed_hashes) {
        // The cast preserves all 64 bits even when Kotlin's signed Long is negative.
        hashes.push_back(static_cast<std::uint64_t>(hash));
    }
    return hashes;
}

std::vector<edgegallery::ImageFingerprint> make_fingerprints(
    const std::vector<std::string>& content_hashes,
    const std::vector<std::uint64_t>& difference_hashes) {
    if (content_hashes.size() != difference_hashes.size()) {
        throw std::invalid_argument("content hash and dHash counts must match");
    }

    std::vector<edgegallery::ImageFingerprint> fingerprints;
    fingerprints.reserve(content_hashes.size());

    for (std::size_t index = 0; index < content_hashes.size(); ++index) {
        fingerprints.push_back({
            std::to_string(index),
            content_hashes[index],
            difference_hashes[index],
            true,
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
        const jint type = group.kind == edgegallery::DuplicateKind::Exact
            ? kExactGroup
            : kVisuallySimilarGroup;

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
    jint java_hamming_threshold) {
    try {
        if (java_hamming_threshold < 0 || java_hamming_threshold > 64) {
            throw std::invalid_argument("Hamming threshold must be between 0 and 64");
        }

        const auto content_hashes = read_strings(environment, java_content_hashes);
        const auto difference_hashes = read_hashes(environment, java_difference_hashes);
        const auto fingerprints = make_fingerprints(content_hashes, difference_hashes);

        edgegallery::ClusterOptions options;
        options.hamming_threshold = static_cast<std::uint32_t>(java_hamming_threshold);
        const auto groups = edgegallery::cluster_duplicates(fingerprints, options);

        return make_java_int_array(environment, encode_groups(groups));
    } catch (const std::invalid_argument& error) {
        throw_java_exception(environment, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception& error) {
        throw_java_exception(environment, "java/lang/RuntimeException", error.what());
    }

    return nullptr;
}
