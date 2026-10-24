# Main Redirect — LSPosed module source

Redirects Java-initiated loads of `libmain.so` in apps selected in LSPosed's scope
to a replacement packaged in this module APK. There is no destination package ID
check or package configuration; choose the apps in LSPosed without rebuilding.
Uses the **legacy Xposed API**; your
LSPosed build must support legacy modules. Android 8.1/API 27 or later is the
declared minimum, not a claim of device testing. There is no launcher activity.

## Configure

1. Put your existing replacement ELF binaries at these paths, as appropriate:

   ```text
   app/src/main/assets/replacements/arm64-v8a/libmain.so
   app/src/main/assets/replacements/armeabi-v7a/libmain.so
   app/src/main/assets/replacements/x86_64/libmain.so
   app/src/main/assets/replacements/x86/libmain.so
   ```

   Supply the ABI of the **target process**, which may be 32-bit on a 64-bit device.
   No replacement binary is included: it must implement the JNI exports and
   behavior expected by your target app. ELF checks verify architecture only;
   the Android linker validates the actual shared library.
2. Open the project in Android Studio with JDK 17, Android SDK 35 and Gradle 8.9.
   The project pins Android Gradle Plugin 8.7.3. A Gradle wrapper is not bundled;
   with Gradle 8.9 installed, run:

   ```sh
   gradle wrapper --gradle-version 8.9
   ./gradlew :app:assembleDebug
   ```

   On Windows use `gradlew.bat :app:assembleDebug` after generating the wrapper.
   The build deliberately fails if no replacement is supplied. Internet access
   is needed to resolve dependencies.
3. Install `app/build/outputs/apk/debug/app-debug.apk`. Enable Main Redirect in
   LSPosed and manually select the target apps in its scope. Force-stop and
   relaunch each selected app. The same bundled replacement is used in each app,
   so it must be compatible with every app you select. Reboot if your framework
   requires it, or after updating the module if
   the old APK path remains cached. Use a normal installed APK, not an AAB or
   asset-delivery split: replacements must be inside the base module APK.

## Behavior

- Hooks `BaseDexClassLoader.findLibrary("main")`, returning the replacement path
  even when no original library is packaged in the target.
- Hooks discovered `Runtime.nativeLoad(String, ClassLoader, ...)` overloads and
  changes only the filename when its basename is exactly `libmain.so`. This
  covers Java absolute-path loads and APK `!/lib/<abi>/libmain.so` paths that
  reach Runtime. Loader and caller-class arguments stay intact, so normal
  linker namespace selection and JNI registration still run in the app's context.
- Copies the selected asset from `initZygote`'s module APK path into the target
  app's `code_cache/mainredirect/<abi>-<sha256>/libmain.so`. A temporary file,
  read-only permissions, and content-based filenames prevent partial publication
  and avoid reusing stale payloads after updates. Different target processes can
  publish identical content concurrently; old versions remain until cache cleanup.
- Installs once per process where LSPosed loads the module, using LSPosed's scope
  selection without checking package IDs. Missing or malformed
  payloads produce `UnsatisfiedLinkError`, rather than loading the original as a
  fallback. Hook installation failures roll back installed hooks and log that
  redirection is inactive. Other library names pass through unchanged.

## Limits

This Java module does **not** intercept native `dlopen`/`android_dlopen_ext`, ELF
`DT_NEEDED` dependencies, or libraries loaded before the hooks are installed.
Custom class loaders overriding `findLibrary` may fail before reaching Runtime
if they cannot resolve `main`; that requires an additional loader-specific hook.
Native bridge/translated ABIs are not supported. An already loaded library cannot
be replaced in place: restart every target process after a module update.

The replacement must be loadable in the target's linker namespace. Private
dependencies need to be available to that namespace; this project copies only
`libmain.so`. Merely placing extra `.so` files beside the asset does not make them
available. Device SELinux policy, isolated processes, locked credential storage,
or a framework denying access to the module APK can also prevent extraction or
loading. Test on your exact Android/LSPosed version. On devices using 16 KB pages,
the replacement and its dependencies must be built with compatible ELF alignment.

## Device verification

After configuring and building, install and scope the module, then trigger the
target's actual JNI call. Look in LSPosed logs for `MainRedirect: hooks installed`
and `MainRedirect: prepared`. Confirm the target process maps the extracted file
(for example inspect `/proc/<pid>/maps` from a root shell), then verify the JNI
behavior implemented by your replacement. A `prepared` log alone proves only
extraction, not successful loading or JNI compatibility. Check a non-main load
still succeeds, restart after replacing the asset, and test each supported ABI.

## References

- Android Runtime loader source:
  https://android.googlesource.com/platform/libcore/+/master/ojluni/src/main/java/java/lang/Runtime.java
- Xposed API: https://api.xposed.info/
- Module APK path:
  https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookZygoteInit.StartupParam.html
- Build tool compatibility:
  https://developer.android.com/build/releases/agp-8-7-0-release-notes

## Validation in the creation environment

Android SDK, Gradle and a working JDK were unavailable, so no APK build or device
test was performed. Configuration/XML/entry-point consistency was inspected.
