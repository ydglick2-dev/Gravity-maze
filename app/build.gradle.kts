import java.util.Properties

plugins {
    // AGP 9 applies the Kotlin plugin itself — `org.jetbrains.kotlin.android`
    // must not be applied on top of it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing. The keystore lives in signing/ so that every build of this
// personal side-loaded app is upgrade-compatible with the previous one.
// Falls back to the debug key if the keystore is absent (e.g. a fresh clone).
val keystorePropsFile = rootProject.file("signing/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.glassify.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.glassify.launcher"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en", "iw")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // The build must not fail on a lint warning, but real errors still block.
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
