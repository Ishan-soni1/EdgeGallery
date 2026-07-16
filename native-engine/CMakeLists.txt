add_library(edgegallery_engine
    src/duplicate_clusterer.cpp
)

add_library(EdgeGallery::engine ALIAS edgegallery_engine)

# The engine is linked into an Android shared library, so its object code must
# be position independent. This setting is harmless for the desktop test build.
set_target_properties(edgegallery_engine PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_include_directories(edgegallery_engine
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
)

target_compile_features(edgegallery_engine PUBLIC cxx_std_17)

if(MSVC)
    target_compile_options(edgegallery_engine PRIVATE /W4 /permissive-)
else()
    target_compile_options(edgegallery_engine PRIVATE -Wall -Wextra -Wpedantic)
endif()

option(EDGEGALLERY_BUILD_TESTS "Build EdgeGallery native tests" ON)

if(EDGEGALLERY_BUILD_TESTS)
    add_executable(edgegallery_engine_tests
        tests/duplicate_clusterer_test.cpp
    )

    target_link_libraries(edgegallery_engine_tests PRIVATE EdgeGallery::engine)
    target_compile_features(edgegallery_engine_tests PRIVATE cxx_std_17)

    if(MSVC)
        target_compile_options(edgegallery_engine_tests PRIVATE /W4 /permissive-)
    else()
        target_compile_options(edgegallery_engine_tests PRIVATE -Wall -Wextra -Wpedantic)
    endif()

    add_test(NAME edgegallery_engine_tests COMMAND edgegallery_engine_tests)
endif()
