cmake_minimum_required(VERSION 3.22.1)
project(vertexa LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# ---- Assimp (BSD-3) for FBX / glTF / GLB / DAE / 3DS / 3MF / DXF / X / OFF / LWO / BLEND ...
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ASSIMP_TOOLS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(ASSIMP_INSTALL OFF CACHE BOOL "" FORCE)
set(ASSIMP_INSTALL_PDB OFF CACHE BOOL "" FORCE)
set(ASSIMP_NO_EXPORT ON CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ZLIB ON CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_DRACO OFF CACHE BOOL "" FORCE)
set(ASSIMP_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(ASSIMP_ANDROID_JNIIOSYSTEM OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT OFF CACHE BOOL "" FORCE)
foreach(fmt FBX GLTF COLLADA 3DS 3MF DXF X OFF LWO LWS BLEND AC MS3D OBJ STL PLY)
    set(ASSIMP_BUILD_${fmt}_IMPORTER ON CACHE BOOL "" FORCE)
endforeach()

FetchContent_Declare(
    assimp
    URL https://github.com/assimp/assimp/archive/refs/tags/v5.4.3.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(assimp)

add_library(vertexa SHARED
    jni_bridge.cpp
    engine.cpp
    renderer.cpp
    loader_obj.cpp
    loader_stl.cpp
    loader_ply.cpp
    loader_assimp.cpp
)

target_compile_options(vertexa PRIVATE -O3 -ffast-math -Wno-unused-parameter)
target_link_libraries(vertexa assimp GLESv3 EGL android log)
