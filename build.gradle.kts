plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
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
        // 대상 IDE: IntelliJ IDEA Community 2024.2.x
        intellijIdeaCommunity("2024.2.5")
        // NEXCORE 는 순수 Java 프레임웍 → Java PSI 만 의존 (Kotlin PSI 불필요)
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "243.*"
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
// 사유: 2024.2.5 의 GradleJvmSupportMatrix 가 최신 jvmcompat 데이터의 "Java 25" 를 파싱 못 해
//       프로젝트 오픈 시 startup activity 가 IllegalArgumentException 으로 깨진다.
//       본 플러그인/Maven 샘플은 Gradle 에 의존하지 않으므로 테스트 샌드박스에서만 끈다(배포 jar 무관).
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
