plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.devbangs.onedevs"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.devbangs.onedevs"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The adaptive icon has to stay in mipmap-anydpi-v26. aapt2 in AGP 9.4
        // does not resolve @mipmap/ic_launcher from an unqualified mipmap-anydpi
        // folder, so lint's suggestion to merge it there breaks resource linking.
        disable += "ObsoleteSdkInt"
    }

    testOptions {
        unitTests {
            // These tests never touch the framework, but a stray android.jar
            // call should come back as a default rather than throwing and
            // being read as a failure in the thing under test.
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
        // Sample rows for the board exist in debug only. The board has no
        // backend yet, and a list component cannot be judged against an empty
        // list -- but shipping invented apps would be worse than shipping none,
        // so the compiler decides rather than a flag someone has to remember.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
