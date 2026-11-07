import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "zj"

pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.21"
        kotlin("plugin.serialization") version "2.4.21"
    }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
  id("org.jetbrains.intellij.platform.settings") version "2.20.0"
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
