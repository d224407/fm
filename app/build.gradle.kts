import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing from keystore.properties (git-ignored)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.fm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.fm"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // arm64 only keeps the APK small
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            // R8 on; JGit is fully kept in proguard-rules.pro (reflection, ServiceLoader)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug") // test installs only, never publish
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/**",
            "/META-INF/MANIFEST.MF",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module",
            "/META-INF/eclipse.inf",
            "/META-INF/ECLIPSE_*",
            "**/module-info.class",
            "about.html",
            "plugin.properties",
        )
    }
}

dependencies {
    // UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Root
    implementation(libs.libsu.core)

    // Archives
    implementation("${libs.zstd.jni.get()}@aar")
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Git
    implementation(libs.jgit)
    implementation(libs.slf4j.jdk14)

    // Xem file
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
}
