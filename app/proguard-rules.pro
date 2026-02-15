# Keep JNI methods referenced from Rust
-keepclasseswithmembernames class * {
    native <methods>;
}
