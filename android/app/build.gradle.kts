plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.daniel_geron.smm2injector"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.daniel_geron.smm2injector"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        // Release builds always use the public MariOver server. Debug builds can be pointed at a
        // test server with -PapiBaseUrl=http://10.0.2.2:8000/mm2
        buildConfigField("String", "API_BASE_URL", "\"https://tgrcode.com/mm2\"")
    }

    buildTypes {
        debug {
            (project.findProperty("apiBaseUrl") as String?)?.let {
                buildConfigField("String", "API_BASE_URL", "\"$it\"")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the APK can be sideloaded without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Archive tests use real files from the dataset; pass -PtestDataDir=... to run them.
tasks.withType<Test>().configureEach {
    systemProperty("testDataDir", project.findProperty("testDataDir") ?: "")
    testLogging { showStandardStreams = true; events("passed", "failed", "skipped") }
}
