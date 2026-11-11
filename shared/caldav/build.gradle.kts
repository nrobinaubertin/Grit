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

// Plain JVM module: CalDAV protocol client, iCalendar helpers and the generic sync engine.
// It has no Android or Koin dependency, so that both the Android app and the desktop app can
// use it, and so that it can be tested with plain JUnit.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(calendarLibs.plugins.animalsniffer)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    // dav4jvm brings xpp3 for desktop. Android has its own XmlPullParser, so the desktop app adds
    // xpp3 back as a runtime dependency (see :shared:calendar jvmMain).
    implementation(calendarLibs.dav4jvm) { exclude(group = "org.ogce", module = "xpp3") }
    implementation(calendarLibs.ktor.client.okhttp)
    implementation(calendarLibs.ktor.client.auth)
    implementation(calendarLibs.ical4j)
    implementation(calendarLibs.commons.lang3)
    implementation(calendarLibs.commons.codec)
    implementation(libs.kotlinx.coroutines)
    // Reads the JSON answers of the Radicale sharing API (no compiler plugin needed)
    implementation(libs.kotlinx.serialization.json)
    // Public API types (dates and times) are kotlinx-datetime, so that common code can use them
    api(libs.kotlinx.datetime)

    // Checks that this module only calls Java APIs that exist on Android API 29
    signature(calendarLibs.android.signature) { artifact { type = "signature" } }

    testImplementation(kotlin("test"))
    testImplementation(platform(calendarLibs.junit.bom))
    testImplementation(calendarLibs.junit.jupiter)
    testImplementation(calendarLibs.ktor.client.mock)
    testImplementation(calendarLibs.coroutines.test)
    testRuntimeOnly(calendarLibs.junit.launcher)
    testRuntimeOnly(calendarLibs.xpp3)
}

animalsniffer {
    // Only the production code runs on Android
    sourceSets = listOf(project.sourceSets.main.get())
}

tasks.test {
    useJUnitPlatform()
    // Integration tests against a real Radicale server run only when RADICALE_URL is set
    System.getenv("RADICALE_URL")?.let { environment("RADICALE_URL", it) }
}

// The root build stamps the upstream copyright header on every Kotlin file. Files of this module
// carry the fork author's header instead, and files copied from DAVx5 keep their own header.
configure<SpotlessExtension> {
    kotlin { targetExclude("src/**/*.kt") }
    format("kotlinFork", KotlinExtension::class.java) {
        target("src/**/*.kt")
        targetExclude("src/**/davx5/**")
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
