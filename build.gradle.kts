import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.0"
}

group = "dev.juniedeveloper"
// The +<sha> suffix pins each build to its commit, so "which build is installed?"
// always has an answer in Settings > Plugins.
val gitShortSha = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }
version = "0.1.0+" + runCatching { gitShortSha.get().ifEmpty { "dev" } }.getOrDefault("dev")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Air exists for 2026.2 (262.8665) and 2026.3, so the oldest of those is
        // the compile base and the floor. No dependency on Air itself: it is reached
        // by reflection (see AirPoolResetter), which keeps this plugin loadable without it.
        intellijIdea("2026.2.5")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.juniedeveloper"
        name = "JunieDeveloper"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            // both IDE generations that have Air
            create(IntelliJPlatformType.IntellijIdea, "2026.2.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Stay within the Kotlin stdlib bundled with the oldest supported IDE;
        // unpinned, the 2.3 compiler can emit calls into newer stdlib intrinsics.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}
