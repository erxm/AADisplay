plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    //kotlin("android")
    id("dev.rikka.tools.refine") version "4.4.0"
}

android {
    val buildTime = System.currentTimeMillis()
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nitsuya.aa.display"
        minSdk = 31
        targetSdk = 36
        versionCode = 3018
        versionName = "0.24#18.1-e4"
        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }

    packaging {
        // 不能整块排除 META-INF/**：libxposed 的模块描述文件（module.prop /
        // java_init.list / scope.list）必须以 META-INF/xposed/ 的形式留在 APK 里，
        // 框架就是按这些 zip entry 判断模块是不是 modern 模块的。
        // 这里逐项列出原先想丢掉的依赖元数据，保持 META-INF/xposed/** 不被波及。
        resources.excludes.addAll(
            arrayOf(
                "META-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/*.kotlin_module",
                "META-INF/*.properties",
                "META-INF/*.txt",
                "META-INF/*.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/androidx/**",
                "META-INF/com/**",
                "META-INF/proguard/**",
                "META-INF/services/**",
                "META-INF/versions/**",
                "kotlin/**",
                // aare-aap / aare-proto 的 .proto 源码只给 protoc 用，不进 APK。
                "**/*.proto"
            )
        )
    }
    signingConfigs {
        create("release") {
            storeFile = file("../key.jks")
            storePassword = System.getenv("KEY_ANDROID")
            keyAlias = "key0"
            keyPassword = System.getenv("KEY_ANDROID")
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEY_ANDROID") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            sourceSets.getByName("main").java.srcDir(File("build/generated/ksp/release/kotlin"))
        }
        getByName("debug") {
            // Keep debug artifacts unminified to avoid AGP warnings and speed up test builds.
            isMinifyEnabled = false
            isShrinkResources = false
            if (System.getenv("KEY_ANDROID") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

    }
    
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = variant.versionName.replace("#", "-")
            output.outputFileName = "aa-display-${versionName}.apk"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources.additionalParameters += mutableListOf("--allow-reserved-package-id", "--package-id", "0x64")

    namespace = "io.github.nitsuya.aa.display"
    buildToolsVersion = "35.0.0"
}

configurations.all {
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
//    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("dev.rikka.rikkax.appcompat:appcompat:1.6.1")
    implementation("dev.rikka.rikkax.material:material-preference:2.0.0")

    //kotlinx-coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    //ViewBindingUtil
    implementation("com.github.matsudamper:ViewBindingUtil:0.1")

    compileOnly(project(":lib-stub"))
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    compileOnly(files("./libs/de.robv.android.xposed_api_82.jar"))
    // libxposed（现代 Xposed API）。api 必须是 compileOnly：框架会检查入口类的
    // XposedModule 是否由框架自己的 ClassLoader 加载，编进 APK 会直接被拒绝加载。
    // 固定在 101：102 的 AAR 要求 compileSdk=37（AGP 8.11.2 只支持到 36）。
    // 101 与 102 的 XposedModule 构造器、四个回调签名、IXposedService 的
    // DESCRIPTOR 与事务码完全一致，attachFramework 由框架在自己加载的 api 类上调用，
    // 所以按 101 编译在 102 框架上同样可用。module.prop 里 targetApiVersion 也声明 101，
    // 这样 de.robv.android.xposed.* 不会被 blockLegacyApi 挡掉（EzXHelper 依赖它）。
    compileOnly("io.github.libxposed:api:101.0.0") {
        // libxposed 的 pom 声明了 kotlin-stdlib 2.2.10，会把项目的 stdlib 顶到
        // 编译器（Kotlin 2.0.0，最多读 2.1.0 元数据）读不了的版本。它的 class
        // 文件里没有任何 kotlin/ 引用，纯 Java，排掉即可。
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    // 模块 App 侧：通过 XposedService 写远端配置，取代世界可读的 XSharedPreferences。
    implementation("io.github.libxposed:service:101.0.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("com.github.kyuubiran:EzXHelper:1.0.3")
    implementation("com.github.topjohnwu.libsu:core:5.2.0")
    // AARE AAP 协议栈（GPL-3.0-or-later，vendored 在 vendor/aare-aap，见 NOTICE.md）。
    implementation(project(":aare-aap"))
    // 2.3.0 起 native 库按 16 KB 页对齐（Android 15+/16 KB 设备必需）；
    // 旧版 rc3 的 libdexkit.so 是 4 KB 对齐，Android 17 会弹兼容性警告并可能加载失败。
    implementation("org.luckypray:dexkit:2.3.0")
//    implementation("com.github.martoreto:aauto-sdk:v4.7")
    implementation(files("./libs/aauto.aar"))

    //lifecycle
    val lifecycleVersion = "2.9.3"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-common-java8:$lifecycleVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

}
