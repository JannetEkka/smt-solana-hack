import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release APK is signed with a demo key committed in signing/. It exists so anyone
// can sideload the hackathon build; it protects nothing. A store build gets its own key.
val demoSigning = Properties().apply { load(rootProject.file("signing/demo-signing.properties").inputStream()) }

android {
    namespace = "io.github.jannetekka.smtworld"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jannetekka.smtworld"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
    }

    signingConfigs {
        create("demo") {
            storeFile = rootProject.file(demoSigning.getProperty("storeFile"))
            storePassword = demoSigning.getProperty("storePassword")
            keyAlias = demoSigning.getProperty("keyAlias")
            keyPassword = demoSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

base { archivesName.set("smt-world-${android.defaultConfig.versionName}") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
