# Keep JNI methods referenced from Rust
-keepclasseswithmembernames class * {
    native <methods>;
}

# Rust constructs these via env.find_class + new_object — R8 must not rename or
# strip the classes or these constructors.
-keep class com.caravanfire.calmqr.rust.DecodeResult {
    <init>(java.lang.String, java.lang.String, java.lang.String, float, float);
}
-keep class com.caravanfire.calmqr.rust.EngineOutput {
    <init>(com.caravanfire.calmqr.rust.DecodeResult, float, float);
}
