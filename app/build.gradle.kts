import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 发布机优先通过环境变量注入签名信息，同时保留本地 keystore.properties 兼容方式。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val releaseStoreFile = System.getenv("PDFINSPECTOR_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("storeFile")
val releaseStorePassword = System.getenv("PDFINSPECTOR_STORE_PASSWORD")
    ?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias = System.getenv("PDFINSPECTOR_KEY_ALIAS")
    ?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("PDFINSPECTOR_KEY_PASSWORD")
    ?.takeIf { it.isNotBlank() }
    ?: keystoreProperties.getProperty("keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "SVS.pdfinspector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lonnnnnng.pdfinspector"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.5.0"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.compose.icons.tabler)
    implementation(libs.pdfbox.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.json)
}
