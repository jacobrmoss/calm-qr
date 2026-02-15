# 1. Install Android SDK + NDK (or open in Android Studio and let it install)
#    Set ANDROID_HOME and ANDROID_NDK_HOME

# 2. Install cargo-ndk and add Android Rust targets
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi

# 3. Build

## Debug
./gradlew assembleDebug

Open the project in Android Studio — it will recognize it as a standard Gradle Android project. The Rust build
is hooked into mergeDebugJniLibFolders / mergeReleaseJniLibFolders, so ./gradlew assembleDebug will
automatically build the Rust .so as well.

## Release
./gradlew assembleRelease


## Install to phone
adb -s MK20250404519 install app/build/outputs/apk/release/app-release.apk




