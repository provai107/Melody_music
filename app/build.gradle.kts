plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.braveplayer.music"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.braveplayer.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val envKeystore = System.getenv("KEYSTORE_FILE")
            storeFile = if (!envKeystore.isNullOrBlank() && file(envKeystore).exists()) {
                file(envKeystore)
            } else {
                file("${rootDir}/release.keystore")
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "braveplayer"
            keyAlias = System.getenv("KEY_ALIAS") ?: "braveplayer"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "braveplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":filter-core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // AndroidX Media3
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-datasource:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // OkHttp & Coroutines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
