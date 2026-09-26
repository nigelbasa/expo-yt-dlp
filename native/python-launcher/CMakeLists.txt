cmake_minimum_required(VERSION 3.22)
project(ytdlp_python C)

set(PYTHON_PREFIX "" CACHE PATH "The prefix/ directory of a python.org Android release")
set(PYTHON_VERSION "3.14" CACHE STRING "Python major.minor version")

if(NOT EXISTS "${PYTHON_PREFIX}/lib/libpython${PYTHON_VERSION}.so")
  message(FATAL_ERROR "libpython${PYTHON_VERSION}.so not found under PYTHON_PREFIX=${PYTHON_PREFIX}")
endif()

add_executable(ytdlp_python main.c)
target_include_directories(ytdlp_python PRIVATE "${PYTHON_PREFIX}/include/python${PYTHON_VERSION}")
target_link_libraries(ytdlp_python PRIVATE "${PYTHON_PREFIX}/lib/libpython${PYTHON_VERSION}.so")

# Must be named lib*.so so the package manager extracts it to nativeLibraryDir.
set_target_properties(ytdlp_python PROPERTIES OUTPUT_NAME "libytdlp_python" SUFFIX ".so")
