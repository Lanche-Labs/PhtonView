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

// 读取上次构建的日期（YYYY-MM-DD）。如果跨天（今天 != 上次构建日期），
// 把 versionBuild 重置为 01，避免"过一天还接着昨天的 22"导致版本号变成
// 26.07.08.22 这种与日期不匹配、看起来像"跳号"的版本。
val lastBuildDate = versionProps.getProperty("versionDate", "")
val currentDateStr = today.toString()
var rawBuild = versionProps.getProperty("versionBuild", "01").toInt().coerceIn(0, 99)
if (lastBuildDate != currentDateStr) {
    rawBuild = 1
    versionProps.setProperty("versionDate", currentDateStr)
    versionProps.setProperty("versionBuild", rawBuild.toString().padStart(2, '0'))
    versionFile.outputStream().use { versionProps.store(it, "PhtonView Version") }
    println("Date changed ($lastBuildDate -> $currentDateStr), reset versionBuild to 01")
}
val versionBuild = rawBuild

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
        resValue("string", "developer_team", "Lanche-Labs")
        buildConfigField("String", "DEVELOPER_TEAM", "\"Lanche-Labs\"")

        // 用户体验改进计划：在 local.properties 中配置 GITHUB_TOKEN 以启用自动提交 Issue
        val localProps = Properties().apply {
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) load(localPropsFile.inputStream())
        }
        val githubToken = (localProps.getProperty("GITHUB_TOKEN")
            ?: project.findProperty("GITHUB_TOKEN") as? String)
            ?: ""
        buildConfigField("String", "GITHUB_TOKEN", "\"$githubToken\"")
        if (githubToken.isBlank()) {
            println("WARNING: GITHUB_TOKEN not configured; UX improvement issue submission is disabled.")
        } else {
            println("GITHUB_TOKEN configured (length=${githubToken.length}, prefix=${githubToken.take(8)}...)")
        }

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
            // Debug 构建使用默认的 debug 签名，避免本地缺少 release keystore 时无法编译。
            // Release 构建仍需使用项目 release 签名配置。
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

// 手动递增 build 号任务：运行 ./gradlew :app:incrementVersion 后提交 version.properties，
// 避免自动递增导致 APK versionName 与仓库 version.properties 不一致。
tasks.register("incrementVersion") {
    doLast {
        val nextBuild = (versionBuild % 99) + 1
        versionProps.setProperty("versionBuild", nextBuild.toString().padStart(2, '0'))
        versionFile.outputStream().use { versionProps.store(it, "PhtonView Version") }
        println("Version bumped to $versionMajor.$versionMinor.$versionPatch.${nextBuild.toString().padStart(2, '0')}")
    }
}

// 将 LICENSE / COPYING / LICENSES/* 复制到 assets/licenses，供应用内许可证页面读取
val copyLicenseAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE" }
    }
    from(rootProject.file("COPYING")) {
        rename { "COPYING" }
    }
    from(rootProject.file("LICENSES")) {
        into("LICENSES")
    }
    into(layout.buildDirectory.dir("generated/assets/licenses"))
}

android.sourceSets["main"].assets.srcDirs(
    file("src/main/assets"),
    copyLicenseAssets.map { it.destinationDir }
)

tasks.whenTaskAdded {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(copyLicenseAssets)
    }
}

// 打包编译时使用的源码，Release 构建默认附带
val packageSource by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages the source code used to build this release."
    archiveFileName.set("app-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/source"))
    from(rootProject.projectDir) {
        exclude("**/build/**", "**/.git/**", "**/.gradle/**", "**/*.apk", "**/*.jks", "**/local.properties", "**/*.log")
        exclude("app/gphoto2/prebuilt/**")
    }
}

tasks.whenTaskAdded {
    if (name == "assembleRelease") {
        dependsOn(packageSource)
    }
}

dependencies {
    // AndroidX 核心
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.animation:animation")
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

    // 测试（迭代 #16：单测基建）
    testImplementation("junit:junit:4.13.2")
    // Robolectric：在 JVM 上跑 Android framework 调用（Bitmap、Context）
    testImplementation("org.robolectric:robolectric:4.11.1")
    // MockK：Kotlin 友好 mock 库
    testImplementation("io.mockk:mockk:1.13.9")
    // Coroutines test：协程调度器替换为 TestDispatcher
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Truth：更可读的断言
    testImplementation("com.google.truth:truth:1.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
