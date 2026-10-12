cmake_minimum_required(VERSION 3.22.1)

project("decorstr_native")

add_library(decorstr_native SHARED
    decorstr_native.cpp
    covariance.cpp
    eigen3x3.cpp
    whitening.cpp
    color_spaces.cpp
)

find_library(log-lib log)
find_library(gles-lib GLESv3)
find_library(egl-lib EGL)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(decorstr_native
    ${log-lib}
    ${gles-lib}
    ${egl-lib}
    ${jnigraphics-lib}
)

# Enforce 16KB ELF page size alignment for Android 15/16 (e.g. Galaxy S26+)
target_link_options(decorstr_native PRIVATE
    "-Wl,-z,max-page-size=16384"
)
