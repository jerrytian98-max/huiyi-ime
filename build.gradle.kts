// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target(
            fileTree("app/src/main/java") { include("**/*.kt") },
            fileTree("app/src/test/java") { include("**/*.kt") },
            fileTree("app/src/androidTest/java") { include("**/*.kt") },
            fileTree("codegen/src") { include("**/*.kt") },
            fileTree("build-logic") {
                include("**/*.kt", "**/*.kts")
                exclude("**/build/**", "**/.gradle/**")
            },
            files("build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", "codegen/build.gradle.kts"),
        )
        ktlint("1.8.0")
    }
}
