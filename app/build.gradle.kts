plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "app.stash"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.stash.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 44
        versionName = "1.25.0"

        // yt-dlp ships a whole python per abi, so only keep arm64 (every recent phone) and
        // x86_64 (the emulator) to stop the apk from ballooning.
        ndk {
            // phones: 64-bit. older android tvs are 32-bit arm, they get their own apk:
            // gradle assembleRelease -Pabis=armeabi-v7a (dist/stash-armv7.apk)
            abiFilters += ((project.findProperty("abis") as String?) ?: "arm64-v8a,x86_64").split(",")
        }

        vectorDrawables {
            useSupportLibrary = true
        }

        // stash's supabase project (accounts + playlist backup). the publishable key is meant to
        // ship in apps; row level security keeps every account to its own rows.
        buildConfigField("String", "SUPABASE_URL", "\"https://mcmnovtqupckpylrnobp.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_uexeqwWBLSc9xTTCDrOUog_TcUe2Or7\"")
    }

    // one fixed key in the repo, so every build (any machine, any session) installs over the last
    // one without wiping your library. it's the same key all earlier stash apks were signed with.
    signingConfigs {
        create("stash") {
            storeFile = file("stash.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stash")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("stash")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time / nio
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        // youtubedl-android unpacks its python from the native lib dir, so libs must be extracted.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // turning off webview's X-Requested-With header for the youtube login
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // soundcloud only streams hls now
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    // re-encodes downloads to smaller aac files on the phone (no ffmpeg needed)
    implementation("androidx.media3:media3-transformer:1.5.1")

    // library + downloads
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // di
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // network + sources
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    // youtube streams in plain java (rhino for the player code), like newpipe / pipepipe / youtube classic:
    // seconds instead of yt-dlp's whole python
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
