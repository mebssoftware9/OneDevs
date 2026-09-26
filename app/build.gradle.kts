import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    bundle {
        language {
            // Every language ships in every install. On by default, Play splits
            // a bundle by language and sends a device only its own -- so the
            // picker would offer French and then render English, because the
            // French resources were never downloaded. An in-app picker and
            // language splitting cannot both be true.
            enableSplit = false
        }
    }

    buildTypes {
        debug {
            // en-XA accents every character and runs about 30% long; ar-XB
            // mirrors the layout. Between them they find truncation and
            // right-to-left mistakes on a real device, months before there is
            // a translator to find them for us. Debug only -- they are test
            // instruments, not languages.
            isPseudoLocalesEnabled = true
        }

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
        // The gate is lint's, not a grep's. Every block that has landed here
        // checked the report for "0 errors, 0 warnings" afterwards, which means
        // a typo in the grep is a gate that passes everything. This fails the
        // build instead.
        warningsAsErrors = true
        abortOnError = true

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

/**
 * Pins Kotlin's bytecode target, which compileOptions only did for javac.
 * Kotlin was taking its target from whichever JDK happened to be running --
 * 17 on the CI runner, 25 in the Codespace -- so the same source compiled two
 * ways depending on where.
 *
 * jvmTarget rather than jvmToolchain deliberately. A toolchain pins the whole
 * JDK and has to provision one, which means a resolver plugin whose version
 * has to stay in step with Gradle's; 0.8.0 already broke against Gradle 9.8
 * over a vendor constant that no longer exists. What this build actually needs
 * is one bytecode level, and compileSdk bounds the API surface regardless of
 * which JDK compiled it.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
