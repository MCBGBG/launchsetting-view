import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.mcbg"
version = "0.1.0"

// JVM 目标由 IntelliJ Platform Gradle Plugin 按平台统一设置（263 → 25）。
// Gradle 需运行在 JDK 25 上：推荐在 ~/.gradle/gradle.properties 里设置
//   org.gradle.java.home=<Rider>/Contents/jbr/Contents/Home
// （用户级配置，不随仓库提交）

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // 优先用本机安装的 Rider（完整 lib 上类路径；marketplace 模块化产物会缺 debugger.shared 等 jar）。
        // 查找顺序：-PriderHome=<路径> → $RIDER_HOME → macOS Toolbox 默认位置。
        // 都没有则回落到 marketplace 产物（可能需要额外补 jar）。
        val riderHome = providers.gradleProperty("riderHome").orNull
            ?: System.getenv("RIDER_HOME")
            ?: listOf(
                System.getProperty("user.home") + "/Applications/Rider.app",
                "/Applications/Rider.app",
            ).firstOrNull { file(it).exists() }
        if (riderHome != null) {
            local(riderHome)
        } else {
            create("RD", "2026.3.0.1")
        }
        bundledPlugin("com.intellij.modules.json")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = "263.*"
        }
    }
}

// 自测开关与项目路径透传：
//   ./gradlew runIde -Dlsplugin.selftest=true -Dlsplugin.testCsproj=<csproj> -Dlsplugin.open=<项目目录>
tasks.withType<RunIdeTask> {
    listOf("lsplugin.selftest", "lsplugin.testCsproj", "lsplugin.dump").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    val open = providers.systemProperty("lsplugin.open").orNull
    if (!open.isNullOrBlank()) {
        args(open)
    }
}
