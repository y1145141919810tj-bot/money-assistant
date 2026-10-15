import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 模板库的单一真源在 ../../templates/templates.json，设备端与云端共用。
// 设备端不允许手改 assets 副本：它由本任务生成，避免两份模板漂移。
val sharedTemplateSource = file("../../templates/templates.json")
val bundledTemplateTarget = file("src/main/assets/templates.json")

tasks.register("syncTemplates") {
    group = "pocketledger"
    description = "Copy the canonical template library into app assets."
    inputs.file(sharedTemplateSource)
    outputs.file(bundledTemplateTarget)
    doLast {
        check(sharedTemplateSource.isFile) {
            "missing canonical template library: ${sharedTemplateSource.invariantSeparatorsPath}"
        }
        bundledTemplateTarget.parentFile.mkdirs()
        sharedTemplateSource.copyTo(bundledTemplateTarget, overwrite = true)
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn("syncTemplates")
    }
}

android {
    namespace = "com.pocketledger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketledger"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { stream ->
            keystoreProperties.load(stream)
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(
                    keystoreProperties.getProperty("storeFile")
                        ?: error("keystore.properties missing storeFile"),
                )
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: error("keystore.properties missing storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: error("keystore.properties missing keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: error("keystore.properties missing keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // JVM unit tests need a real org.json; the android.jar stub throws otherwise.
    testImplementation("org.json:json:20240303")
}
