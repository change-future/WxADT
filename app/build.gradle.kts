
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 读取本地私人配置（local.properties 已在 .gitignore 中，不会上传 GitHub）
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}

android {
    namespace = "com.plug.wxadt"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.plug.wxadt"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // CI 环境：从环境变量读取（GitHub Actions Secrets）
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // debug 构建：从 local.properties 读取私人默认值
            buildConfigField("String", "DEFAULT_AI_KEY",
                "\"${localProps.getProperty("default_ai_key", "")}\"")
            buildConfigField("String", "DEFAULT_VOICE_URL",
                "\"${localProps.getProperty("default_voice_url", "http://localhost:8000/convert")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // release 构建：不内置任何私人信息，用户自行在面板中填写
            buildConfigField("String", "DEFAULT_AI_KEY", "\"\"")
            buildConfigField("String", "DEFAULT_VOICE_URL", "\"http://localhost:8000/convert\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

val targetPackageName = "com.tencent.mm" // 替换为你要 Hook 的 App 包名

tasks.configureEach {
    if (name == "installDebug") {
        doLast {
            println("模块安装完成，正在强行停止目标应用: $targetPackageName")
            try {
                // 使用原生的 ProcessBuilder 运行 adb 命令，彻底告别 Unresolved reference 报错
                ProcessBuilder("adb", "shell", "am", "force-stop", targetPackageName)
                    .start()
                    .waitFor()

                println("🎉 强行停止指令已发送！")

                // (可选) 自动重新打开微信：
                /*
                ProcessBuilder("adb", "shell", "monkey", "-p", targetPackageName, "-c", "android.intent.category.LAUNCHER", "1")
                    .start()
                    .waitFor()
                */
            } catch (e: Exception) {
                println("⚠️ 执行 ADB 失败: ${e.message}")
            }
        }
    }
}