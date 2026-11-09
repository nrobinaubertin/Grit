/*
 * Copyright (C) 2026  Niels Robin-Aubertin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
import com.diffplug.gradle.spotless.KotlinExtension
import com.diffplug.gradle.spotless.KotlinGradleExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Calendar tab: Room storage for synced calendars, sync scheduling, reminders and Compose screens.
// The CalDAV protocol and the sync engine live in :shared:caldav.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.room)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-sensitive-resolution")
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }

    // :shared:caldav is compiled for Java 21
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

    android {
        namespace = "com.shub39.grit.calendar"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_21 }

        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            // Both targets run on the JVM, so common code can use this JVM-only module
            implementation(projects.shared.caldav)
            implementation(projects.shared.core)
            implementation(projects.shared.ui)

            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.calendar)

            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines)

            implementation(libs.androidx.room.runtime)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
            implementation(libs.koin.annotations)
        }
        androidMain.dependencies { implementation(calendarLibs.work.runtime) }
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.swing)
            // XmlPullParser for dav4jvm on desktop (Android has its own)
            runtimeOnly(calendarLibs.xpp3)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
}

room3 { schemaDirectory("$projectDir/schemas") }

// Same header override as in :shared:caldav
configure<SpotlessExtension> {
    kotlin { targetExclude("src/**/*.kt") }
    format("kotlinFork", KotlinExtension::class.java) {
        target("src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
        licenseHeaderFile(rootProject.file("spotless/copyright-calendar.txt"))
    }
    kotlinGradle { targetExclude("*.kts") }
    format("kotlinGradleFork", KotlinGradleExtension::class.java) {
        target("*.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
        licenseHeaderFile(
            rootProject.file("spotless/copyright-calendar.txt"),
            "(^(?![\\/ ]\\*).*$)",
        )
    }
}
