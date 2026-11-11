plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "usb.multitool"
    compileSdk = 36

    defaultConfig {
        applicationId = "usb.multitool"
        minSdk = 28
        targetSdk = 36
        // Single source of the app version; the header shows the installed values.
        // Bump versionCode by 1 when a new installable version is released (not on every
        // CI build). versionName follows MAJOR.MINOR.PATCH and never decreases.
        // See README → App versions.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
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
}
