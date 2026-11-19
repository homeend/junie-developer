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
        // Air ships only for 2026.3+ (its plugin.xml: since-build 263.7307), so that
        // is the floor here too. No dependency on Air itself: it is reached by
        // reflection (see AirPoolResetter), which keeps this plugin loadable without it.
        intellijIdea("2026.3")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.juniedeveloper"
        name = "JunieDeveloper"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

kotlin {
    jvmToolchain(21)
}
