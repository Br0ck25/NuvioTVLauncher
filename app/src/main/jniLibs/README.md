# Bundled libc++_shared.so

These are the `libc++_shared.so` copies shipped inside `io.github.abdallahmehiz:mpv-android-lib` 0.2.1
(taken from its AAR, `jni/<abi>/libc++_shared.so`).

Why: the app has `pickFirsts += "lib/*/libc++_shared.so"`, so only one copy is packaged. `libvlc-all`
also ships one, built from an older libc++ that does not export `std::__ndk1::__hash_memory`. When
VLC's copy won, `libmpv.so` failed to load with
`dlopen failed: cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm"`.
Project jniLibs take priority over dependency copies, so the mpv-matched runtime is packaged.

When `mpv-android-lib` is upgraded, replace these files with the ones from the new AAR.
