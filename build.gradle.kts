plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.nexcore"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 최신 SDK로 빌드(2025.3 / build 253). sinceBuild 은 242 로 낮춰 2024.2 까지 하위호환.
        // useInstaller=false → .dmg 설치본 대신 intellij-repository 의 zip 아티팩트 사용(해당 버전은 zip 만 존재)
        intellijIdeaCommunity("2025.3") {
            useInstaller = false
        }
        // NEXCORE 는 순수 Java 프레임웍 → Java PSI 만 의존
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "253.*"
        }
    }

    // 호환성 검증: 지원 범위(2024.2 ~ 2025.3)의 권장 IDE 들에 대해 plugin verifier 실행
    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// 설정 UI 가 없는 플러그인 — searchable options 인덱스 생성 불필요(헤드리스 IDE 기동/충돌 방지)
tasks.named("buildSearchableOptions") {
    enabled = false
}

// runIde 샌드박스에서 번들 Gradle 플러그인 비활성화.
// 사유: 일부 IDE 빌드의 GradleJvmSupportMatrix 가 최신 jvmcompat 데이터의 Java 버전을 파싱 못 해
//       프로젝트 오픈 시 startup activity 가 예외로 깨진다. 본 플러그인/Maven 샘플은 Gradle 미의존.
val writeDisabledPlugins = tasks.register("writeDisabledPlugins") {
    dependsOn("prepareSandbox")
    doLast {
        val root = layout.buildDirectory.dir("idea-sandbox").get().asFile
        root.listFiles()?.filter { it.isDirectory }?.forEach { product ->
            val config = product.resolve("config").apply { mkdirs() }
            config.resolve("disabled_plugins.txt").writeText("org.jetbrains.plugins.gradle\n")
        }
    }
}
tasks.named("runIde") { dependsOn(writeDisabledPlugins) }
