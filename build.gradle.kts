// Root build script — common config for all subprojects.

plugins {
    java
    id("com.diffplug.spotless") version "7.0.4"
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
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
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
    }

    // Code style — lightweight Spotless rules.
    //
    // We deliberately do NOT use a heavy structural formatter (palantir-java-format
    // / google-java-format). Both depend on internal javac APIs whose signatures
    // change between JDK versions, producing fragile build failures
    // (NoSuchMethodError on Log$DeferredDiagnosticHandler etc.) when contributors
    // run on different JDKs. The codebase already uses consistent 4-space
    // indentation enforced via IDE/.editorconfig conventions, and the rules below
    // catch the cross-cutting hygiene drifts that commonly slip into PRs.
    //
    // Format on demand: ./gradlew spotlessApply
    // CI gate: ./gradlew spotlessCheck (also runs as part of `check`)
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            importOrder("java", "javax", "", "com.portfolio")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
