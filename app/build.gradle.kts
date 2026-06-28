import java.util.Properties
import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

// 版本号：Major/Minor/Patch 跟随构建日期自动更新，Build 号从 version.properties 递增
val versionFile = rootProject.file("app/version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) {
        load(versionFile.inputStream())
    }
}

val today = LocalDate.now()
val versionMajor = today.year % 100
val versionMinor = today.monthValue
val versionPatch = today.dayOfMonth
val versionBuild = versionProps.getProperty("versionBuild", "01").toInt().coerceIn(0, 99)

val versionNameString = String.format(
    "%02d.%02d.%02d.%02d",
    versionMajor,
    versionMinor,
    versionPatch,
    versionBuild
)
// versionCode 必须是整数，格式：YYMMDDVV
val versionCodeNumber = versionMajor * 1_000_000 + versionMinor * 10_000 + versionPatch * 100 + versionBuild

android {
    namespace = "com.phtontools.phtonview"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.phtontools.phtonview"
        minSdk = 24
        targetSdk = 34
        versionCode = versionCodeNumber
        versionName = versionNameString

        // 开发团队信息
        resValue("string", "developer_team", "LCStudio")
        buildConfigField("String", "DEVELOPER_TEAM", "\"LCStudio\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DGPHOTO2_ROOT=${projectDir}/gphoto2/prebuilt"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/phtonview.jks")
            storePassword = "phtonview123"
            keyAlias = "phtonview"
            keyPassword = "phtonview123"
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
            signingConfig = signingConfigs.getByName("release")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            keepDebugSymbols += listOf("**/*.so")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// 每次编译前自动递增 build 号，供下一次使用；超过 99 时回绕到 1
tasks.register("incrementVersion") {
    doLast {
        val nextBuild = (versionBuild % 99) + 1
        versionProps.setProperty("versionBuild", nextBuild.toString().padStart(2, '0'))
        versionFile.outputStream().use { versionProps.store(it, "PhtonView Version") }
        println("Version bumped to $versionMajor.$versionMinor.$versionPatch.${nextBuild.toString().padStart(2, '0')}")
    }
}

tasks.named("preBuild") {
    dependsOn("incrementVersion")
}

dependencies {
    // AndroidX 核心
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")

    // 生命周期与 ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Hilt 依赖注入
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Coroutines 与 Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 图片处理（用于峰值对焦、直方图）
    implementation("androidx.core:core-ktx:1.12.0")

    // EXIF metadata
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
