import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
  id("java")
  id("org.jetbrains.intellij.platform")
  kotlin("jvm")
  kotlin("plugin.serialization")
}

// Include the generated files in the source set
sourceSets {
  main {
    java {
      srcDirs("src/main/gen")
    }
  }
}

dependencies {
  intellijPlatform {
    intellijIdea("2026.3")
    bundledPlugin("com.intellij.gradle")
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    bundledModule("intellij.spellchecker")

    testFramework(TestFrameworkType.Platform)
  }

  testImplementation("junit:junit:4.13.2")
}

kotlin {
  compilerOptions {
    jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
  }
}
