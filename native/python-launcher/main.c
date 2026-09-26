// Minimal `python` executable for Android.
//
// The official python.org Android release only ships libpython (embedded
// mode). This launcher is the same thing CPython's own Programs/python.c does,
// linked against that libpython, so the module can run yt-dlp as a regular
// child process. It is packaged as lib<name>.so inside jniLibs so that Android
// installs it into nativeLibraryDir, the only app location allowed to exec.
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
