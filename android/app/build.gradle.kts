plugins {
    id("com.android.application")
}

val configuredSpaceUrl = (providers.gradleProperty("HF_SPACE_URL").orNull
    ?: System.getenv("HF_SPACE_URL")
    ?: "").trim().trimEnd('/')

android {
    namespace = "com.aibale.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aibale.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "HF_SPACE_URL",
            "\"${configuredSpaceUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
