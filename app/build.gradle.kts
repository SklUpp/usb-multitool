plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// SectorForge sf-core rescue engine: Rust 1.99.1 bridge is a static library built by
// cargo and linked into libusb_native.so (see src/main/cpp/CMakeLists.txt).
val sfRustToolchain = "1.99.1"
val sfBridgeTarget = "aarch64-linux-android"
val sfBridgeDir = layout.projectDirectory.dir("src/main/rust/sf-bridge")
val sfBridgeLib = layout.buildDirectory.file("rust/$sfBridgeTarget/release/libsf_bridge.a")

val buildSfBridge =
    tasks.register<Exec>("buildSfBridge") {
        group = "build"
        description = "Builds the SectorForge sf-core rescue bridge (Rust static library)"
        workingDir = sfBridgeDir.asFile
        environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("rust").get().asFile.absolutePath)
        commandLine("cargo", "+$sfRustToolchain", "build", "--locked", "--release", "--target", sfBridgeTarget)
        inputs.dir(sfBridgeDir)
        inputs.dir(layout.projectDirectory.dir("../third_party/sectorforge/crates/sf-core/src"))
        inputs.file(layout.projectDirectory.file("../third_party/sectorforge/crates/sf-core/Cargo.toml"))
        inputs.file(layout.projectDirectory.file("../scripts/patches/sectorforge-progress-map.patch"))
        inputs.file(layout.projectDirectory.file("../third_party/sectorforge/Cargo.toml"))
        outputs.file(sfBridgeLib)
    }

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(buildSfBridge) }

android {
    namespace = "usb.multitool"
    compileSdk = 36

    defaultConfig {
        applicationId = "usb.multitool"
        minSdk = 28
        targetSdk = 36
        // Fallback app version. Release workflow overrides both values through Gradle
        // properties: versionCode = 1000 + run number, versionName = tag without v.
        // Local and debug builds keep these checked-in defaults.
        // See README → App versions.
        val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()
        val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
        require(releaseVersionCode == null || releaseVersionCode > 0) {
            "releaseVersionCode must be a positive integer"
        }
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName?.takeIf { it.isNotBlank() } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                arguments += listOf("-DSF_BRIDGE_LIB=${sfBridgeLib.get().asFile.absolutePath}")
                fun flag(prop: String, cmake: String) {
                    if (project.findProperty(prop) == "true") {
                        arguments += "-D$cmake=ON"
                    }
                }
                flag("usbWithFatfs", "USB_WITH_FATFS")
                flag("usbWithNtfs", "USB_WITH_NTFS")
                flag("usbWithLwext4", "USB_WITH_LWEXT4")
                if (project.findProperty("usbRequireEngines") == "true") {
                    arguments += "-DUSB_REQUIRE_ENGINES=ON"
                }
            }
        }
    }

    val releaseKeystorePath = System.getenv("USB_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("USB_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("USB_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("USB_KEY_PASSWORD")

    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    val releaseSigning = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
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

    ndkVersion = libs.versions.ndk.get()

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest, themes and resources on the JVM.
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
            }
        }
    }
}

// Pre-resolve android-all jars so Robolectric never hits Maven at test time.
// Separate configs: one module+three versions in one config collapses to newest.
fun robolectricSdkConfig(name: String) =
    configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

val robolectricSdk33 = robolectricSdkConfig("robolectricSdk33")
val robolectricSdk31 = robolectricSdkConfig("robolectricSdk31")
val robolectricSdk28 = robolectricSdkConfig("robolectricSdk28")

val syncRobolectricSdks =
    tasks.register<Sync>("syncRobolectricSdks") {
        from(robolectricSdk33, robolectricSdk31, robolectricSdk28)
        into(layout.buildDirectory.dir("robolectric-sdks"))
    }

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricSdks)
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath,
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.graphics.path)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // SDKs used by robolectric.properties (33) and @Config overrides (28, 31).
    add("robolectricSdk33", "org.robolectric:android-all-instrumented:13-robolectric-9030017-i7")
    add("robolectricSdk31", "org.robolectric:android-all-instrumented:12-robolectric-7732740-i7")
    add("robolectricSdk28", "org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
}
