// Root build script — common config for all subprojects.

import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone") version "4.1.0"
}

allprojects {
    group = "com.portfolio.singleflight"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "net.ltgt.errorprone")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "errorprone"("com.google.errorprone:error_prone_core:2.39.0")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))

        // ErrorProne — compile-time static analysis (Google).
        // Catches common Java traps (equals/hashCode mismatch, format string
        // errors, Future ignored, etc.) before code review even sees them.
        //
        // Disabled on test sources: tests legitimately use patterns that
        // ErrorProne flags (deliberate equality checks, intentional throws),
        // and contract tests are already a strong signal of behavior.
        options.errorprone.disableWarningsInGeneratedCode.set(true)
        if (name.contains("Test", ignoreCase = true)) {
            options.errorprone.isEnabled.set(false)
        }
    }
}
