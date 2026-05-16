import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "intellij-zig"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
  id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    intellijPlatform {
      defaultRepositories()
    }
    maven("https://www.jetbrains.com/intellij-repository/releases")
  }
}
