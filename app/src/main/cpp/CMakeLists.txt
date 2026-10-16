cmake_minimum_required(VERSION 3.22.1)

project("nativescan2")

# OpenCV viene del AAR de Maven Central (prefab). Ver app/build.gradle.kts.
find_package(OpenCV REQUIRED COMPONENTS OpenCV::opencv_java4)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3 -ffast-math)

target_link_libraries(${CMAKE_PROJECT_NAME}
        OpenCV::opencv_java4
        jnigraphics
        android
        log)

# Soporte páginas de 16 KB
set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES
        LINK_FLAGS "-Wl,-z,max-page-size=16384")
