import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ── Version management ──────────────────────────────────────────────────────
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val vMajor = versionProps["VERSION_MAJOR"].toString().toInt()
val vMinor = versionProps["VERSION_MINOR"].toString().toInt()
val vPatch = versionProps["VERSION_PATCH"].toString().toInt()
val vCode  = versionProps["VERSION_CODE"].toString().toInt()
val vName  = "$vMajor.$vMinor.$vPatch"

android {
    namespace = "com.caravanfire.calmqr"
    //noinspection GradleDependency
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.caravanfire.calmqr"
        minSdk = 28
        @Suppress("ExpiredTargetSdkVersion", "OldTargetApi")
        targetSdk = 31 // Android 12 for Mudita Kompakt — NOT targeting Google Play
        versionCode = vCode
        versionName = vName

        ndk {
            // Mudita Kompakt uses MediaTek MT6761V (ARM64) — not targeting ChromeOS/x86
            @Suppress("ChromeOsAbiSupport")
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Suppress Google Play-specific lint warnings (targeting degoogled device)
    lint {
        disable += listOf(
            "OldTargetApi",
            "GradleDependency",
            "ChromeOsAbiSupport",
            "ExpiredTargetSdkVersion"
        )
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                load(file("local.properties").inputStream())
            }
            storeFile = file(props["RELEASE_STORE_FILE"] as String)
            storePassword = props["RELEASE_STORE_PASSWORD"] as String
            keyAlias = props["RELEASE_KEY_ALIAS"] as String
            keyPassword = props["RELEASE_KEY_PASSWORD"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Include pre-built Rust .so libraries from cargo-ndk output
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Rename release APK → calm-qr-v1.0.0.apk
    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (buildType.name == "release") {
                out.outputFileName = "calm-qr-v${versionName}.apk"
            }
        }
    }
}

// ── Rust / cargo-ndk integration ────────────────────────────────────────────
// Prerequisites:
//   cargo install cargo-ndk
//   rustup target add aarch64-linux-android armv7-linux-androideabi
//
// Set ANDROID_NDK_HOME in local.properties or as an environment variable.

val rustDir = rootProject.projectDir.resolve("rust")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")

// Read NDK path from local.properties or environment
val localPropsFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProperties.load(it) }
}
val ndkDir = localProperties.getProperty("ndk.dir")
    ?: System.getenv("ANDROID_NDK_HOME")
    ?: android.ndkDirectory.absolutePath

fun cargoNdkBuild(buildType: String) {
    val cargoProfile = if (buildType == "release") "--release" else ""
    val targets = listOf("arm64-v8a", "armeabi-v7a")

    targets.forEach { abi ->
        val rustTarget = when (abi) {
            "arm64-v8a" -> "aarch64-linux-android"
            "armeabi-v7a" -> "armv7-linux-androideabi"
            else -> error("Unsupported ABI: $abi")
        }

        exec {
            workingDir = rustDir
            environment("ANDROID_NDK_HOME", ndkDir)
            commandLine(
                "cargo", "ndk",
                "--target", rustTarget,
                "--platform", "28",
                "--output-dir", jniLibsDir.absolutePath,
                "build",
                *(if (cargoProfile.isNotEmpty()) arrayOf(cargoProfile) else emptyArray())
            )
        }
    }
}

tasks.register("buildRustDebug") {
    description = "Build Rust native library (debug) via cargo-ndk"
    doLast { cargoNdkBuild("debug") }
}

tasks.register("buildRustRelease") {
    description = "Build Rust native library (release) via cargo-ndk"
    doLast { cargoNdkBuild("release") }
}

// Wire Rust builds into the Android build lifecycle
tasks.whenTaskAdded {
    if (name == "mergeDebugJniLibFolders") dependsOn("buildRustDebug")
    if (name == "mergeReleaseJniLibFolders") dependsOn("buildRustRelease")
}

// ── Version bumping ─────────────────────────────────────────────────────────
fun bumpVersion(bumpMajor: Boolean = false, bumpMinor: Boolean = false, bumpPatch: Boolean = true) {
    val file = rootProject.file("version.properties")
    val props = Properties().apply { file.inputStream().use { load(it) } }
    var major = props["VERSION_MAJOR"].toString().toInt()
    var minor = props["VERSION_MINOR"].toString().toInt()
    var patch = props["VERSION_PATCH"].toString().toInt()
    var code  = props["VERSION_CODE"].toString().toInt()

    when {
        bumpMajor -> { major++; minor = 0; patch = 0 }
        bumpMinor -> { minor++; patch = 0 }
        bumpPatch -> patch++
    }
    code++

    props["VERSION_MAJOR"] = major.toString()
    props["VERSION_MINOR"] = minor.toString()
    props["VERSION_PATCH"] = patch.toString()
    props["VERSION_CODE"]  = code.toString()
    file.outputStream().use { props.store(it, null) }
    println("Version bumped → $major.$minor.$patch (code $code)")
}

tasks.register("bumpPatch") {
    description = "Bump patch version (e.g. 1.0.0 → 1.0.1)"
    doLast { bumpVersion(bumpPatch = true) }
}

tasks.register("bumpMinor") {
    description = "Bump minor version (e.g. 1.0.x → 1.1.0)"
    doLast { bumpVersion(bumpMinor = true) }
}

tasks.register("bumpMajor") {
    description = "Bump major version (e.g. 1.x.x → 2.0.0)"
    doLast { bumpVersion(bumpMajor = true) }
}

configurations.all {
    // Exclude Google Play Services for degoogled devices
    exclude(group = "com.google.android.gms")
    exclude(group = "com.google.firebase")
    exclude(group = "com.google.android.play")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Mudita Mindful Design — e-ink optimized Compose components
    implementation(libs.mudita.mmd)

    debugImplementation(libs.androidx.ui.tooling)
}
