plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.retroidsettings.lineage"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.retroidsettings.lineage"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
    }

    // Release key from ~/.gradle/gradle.properties (RS_STORE_FILE, RS_STORE_PASSWORD, RS_KEY_ALIAS,
    // RS_KEY_PASSWORD), kept outside the project. Without it the release build uses the debug key.
    val releaseKey = (project.findProperty("RS_STORE_FILE") as String?)?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey
            storePassword = project.property("RS_STORE_PASSWORD") as String
            keyAlias = project.property("RS_KEY_ALIAS") as String
            keyPassword = project.property("RS_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
        }
        debug { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    // Lint crashes on this Kotlin/AGP combination; it is not needed for a personal build.
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Material 3 Expressive (1.5.0-alpha01 is the newest build that still compiles against SDK 35)
    implementation("androidx.compose.material3:material3:1.5.0-alpha01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
}
