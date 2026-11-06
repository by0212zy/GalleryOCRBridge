plugins {
    id("com.android.application")
}

android {
    namespace = "com.miui.gallery.ocrbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miui.gallery.ocrbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        // 保持不混淆：Hook 点全部是按字符串反射查找的，开混淆也能用，但关掉最省心。
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // 现代 Xposed API 的入口/作用域/配置都放在 META-INF/xposed/ 下
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        // 纯后台 Xposed 模块没有 UI，release 构建不需要 lintVital，
        // 也避免 CI 为此额外拉取 lint-gradle 依赖
        checkReleaseBuilds = false
    }
}

dependencies {
    // 现代（libxposed）API，只参与编译，由框架在运行时提供
    compileOnly("io.github.libxposed:api:102.0.0")
    // API 类上带有 androidx 注解，编译期需要
    compileOnly("androidx.annotation:annotation:1.9.1")
}
