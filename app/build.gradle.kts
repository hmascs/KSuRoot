plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名参数从用户级 gradle.properties（GRADLE_USER_HOME）读取，不写入项目仓库
val keystoreStoreFile = providers.gradleProperty("KSU_ROOT_STORE_FILE").orNull
val keystoreStorePassword = providers.gradleProperty("KSU_ROOT_STORE_PASSWORD").orNull
val keystoreKeyAlias = providers.gradleProperty("KSU_ROOT_KEY_ALIAS").orNull
val keystoreKeyPassword = providers.gradleProperty("KSU_ROOT_KEY_PASSWORD").orNull

android {
    namespace = "com.ting.root"
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.ting.root"
        minSdk = 33
        targetSdk = 36
        versionCode = 320
        versionName = "3.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    signingConfigs {
        if (keystoreStoreFile != null) {
            create("release") {
                storeFile = file(keystoreStoreFile)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (keystoreStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        // **关键**：libbs.so 是第三方预编译的提权载荷，不是本工程的 JNI 库。
        // AGP 默认会对 jniLibs 里的 .so 跑 strip（实测 162328 → 142848 字节，
        // 符号表被削掉），而这份载荷是**别人编译好的二进制**：
        //  ① 载荷构建页要把它和「原始 v1.0.0」的基线常量逐一对上，
        //     打包进来的必须是原件，不能是 AGP 二次加工过的版本；
        //  ② 任何对预编译载荷的隐式改写都可能让它在真机上失效 ——
        //     这正是「内置动态库不可用」最隐蔽的一层原因。
        // keepDebugSymbols 是 AGP 提供的「不要 strip 这个库」开关。
        jniLibs.keepDebugSymbols += "**/libbs.so"
        // 通用方案（IonStack 编译产物）同理：它是我们交叉编译出来的载荷，
        // 打包进来必须与构建产物逐字节一致，offset 改写才对得上。
        jniLibs.keepDebugSymbols += "**/libionstack.so"
        // 同理还有执行载荷的 helper：它也是第三方预编译二进制。
        jniLibs.keepDebugSymbols += "**/libcve43499root.so"
        // 随包内置的厂商载荷（Xiaomi / vivo 各机型 × 各内核版本，共 38 份）。
        // 它们全部是**别人编译好的二进制**，靠编译期常量寻址内核符号：
        // 一旦被 strip，符号表与常量布局都会变，映射表里登记的 sha256 立刻失配，
        // 设备就再也匹配不到自己那一份。统一通配保护，避免逐个枚举漏项。
        jniLibs.keepDebugSymbols += "**/libksu_*.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Miuix：MIUI / HyperOS 风格 Compose 组件库（含核心 UI 与 Preference）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")

    // Backdrop：底栏/滑块的模糊 + **折射（lens）** —— Apple 液态玻璃的核心。
    // 依赖 RuntimeShader（API 33+），与本工程 minSdk = 33 正好吻合。
    implementation("io.github.kyant0:backdrop-android:2.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

/**
 * 把宿主环境变量 `KSU_TEST_BOOT_IMG` 透进单测 JVM。
 *
 * [为什么必须有这段] `Lz4Test` 的端到端用例读的是 `System.getenv("KSU_TEST_BOOT_IMG")`，
 * 而 Gradle 的 `Test` 任务**默认不继承**调用方的环境变量。结果是：
 * 即使按 `e2e_check.sh` 的用法把变量设上，用例也永远走 `assumeTrue` 分支被 SKIP ——
 * 于是"两个真机镜像端到端通过"这件事，实际上**从来没有被这条自动化验证复现过**，
 * 只能靠人手跑。这是一个安静的假绿灯：报告里显示 SKIPPED，不看细节就当成过了。
 *
 * 只在变量真的存在时才设，避免往测试环境里塞一个空值（空值会让
 * `assumeTrue(!path.isNullOrBlank())` 的语义变得含糊）。
 */
tasks.withType<Test>().configureEach {
    val bootImg = providers.environmentVariable("KSU_TEST_BOOT_IMG").orNull
    if (!bootImg.isNullOrBlank()) {
        environment("KSU_TEST_BOOT_IMG", bootImg)
    }
}
