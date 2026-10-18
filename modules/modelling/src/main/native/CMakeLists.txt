cmake_minimum_required(VERSION 3.22)

project(kasuga_box3d_jni VERSION 0.1.0 LANGUAGES C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT KASUGA_NATIVE_CLASSIFIER)
    set(KASUGA_NATIVE_CLASSIFIER "unknown")
endif()
set(KASUGA_NATIVE_OUTPUT "${CMAKE_BINARY_DIR}/resources/native/${KASUGA_NATIVE_CLASSIFIER}")
file(MAKE_DIRECTORY "${KASUGA_NATIVE_OUTPUT}")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build Box3D statically into the JNI bridge" FORCE)
set(BOX3D_SAMPLES OFF CACHE BOOL "" FORCE)
set(BOX3D_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(BOX3D_DOCS OFF CACHE BOOL "" FORCE)
set(BOX3D_PROFILE OFF CACHE BOOL "" FORCE)
set(BOX3D_VALIDATE OFF CACHE BOOL "" FORCE)
set(BOX3D_UNIT_TESTS OFF CACHE BOOL "" FORCE)

# CMake 4.4's FindJNI passes backslash-joined Windows paths through macros, which
# mangles them into invalid escapes unless CMP0219 is set to NEW. Guarded so
# older local CMake versions without this policy keep working.
if(POLICY CMP0219)
    cmake_policy(SET CMP0219 NEW)
endif()

find_package(JNI REQUIRED)
add_subdirectory(box3d)

add_library(kasuga_box3d SHARED kasuga_box3d_jni.c)
target_compile_features(kasuga_box3d PRIVATE c_std_17)
target_include_directories(kasuga_box3d PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(kasuga_box3d PRIVATE box3d::box3d ${JNI_LIBRARIES})

# Target-level output directories: the vendored box3d/CMakeLists.txt resets the
# directory-scoped CMAKE_{LIBRARY,RUNTIME}_OUTPUT_DIRECTORY to its own bin/, so
# pin the output location on the target itself (takes precedence) and guarantee
# the directory exists before the linker runs.
file(MAKE_DIRECTORY "${KASUGA_NATIVE_OUTPUT}")
# The $<1:...> generator expression stops multi-config generators (Visual
# Studio, Xcode, Ninja Multi-Config) from appending a per-config subdirectory,
# so the library always lands directly under native/<classifier>/ where the
# Gradle test wiring (-Dkasuga.box3d.library) and jar packaging expect it.
set_target_properties(kasuga_box3d PROPERTIES
    OUTPUT_NAME "kasuga_box3d"
    LIBRARY_OUTPUT_DIRECTORY "$<1:${KASUGA_NATIVE_OUTPUT}>"
    RUNTIME_OUTPUT_DIRECTORY "$<1:${KASUGA_NATIVE_OUTPUT}>"
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

if(APPLE)
    set_target_properties(kasuga_box3d PROPERTIES INSTALL_RPATH "@loader_path")
elseif(UNIX)
    set_target_properties(kasuga_box3d PROPERTIES INSTALL_RPATH "$ORIGIN")
endif()
