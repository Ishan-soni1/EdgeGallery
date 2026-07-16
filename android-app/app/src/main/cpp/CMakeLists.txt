cmake_minimum_required(VERSION 3.22.1)

project(EdgeGalleryJni LANGUAGES CXX)

# Walk from android-app/app/src/main/cpp back to the repository root.
get_filename_component(
    EDGEGALLERY_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../../.."
    ABSOLUTE
)

# Desktop CI builds native tests. The Android APK only needs the engine library.
set(EDGEGALLERY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(
    "${EDGEGALLERY_ROOT}/native-engine"
    "${CMAKE_BINARY_DIR}/native-engine"
)

# SHARED produces libedgegallery_jni.so, which Kotlin loads at runtime.
add_library(edgegallery_jni SHARED jni_bridge.cpp)
target_link_libraries(edgegallery_jni PRIVATE EdgeGallery::engine)
target_compile_features(edgegallery_jni PRIVATE cxx_std_17)

target_compile_options(edgegallery_jni PRIVATE -Wall -Wextra -Wpedantic)
