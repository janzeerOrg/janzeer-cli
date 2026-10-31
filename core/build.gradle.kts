import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.clikt)
            implementation(libs.mordant)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            api(libs.janzeer.sdk.core)
            api(libs.janzeer.sdk.client)
            implementation(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.launcher)
            implementation(libs.mockwebserver)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// BuildInfo.VERSION is what `jz version` prints; the Gradle version is what the archives are named after. Keep them equal.
val checkVersion by tasks.registering {
    val src = layout.projectDirectory.file("src/commonMain/kotlin/org/janzeer/jz/BuildInfo.kt")
    val expected = project.version.toString()
    inputs.file(src); inputs.property("expected", expected)
    doLast {
        val line = src.asFile.readLines().firstOrNull { it.contains("const val VERSION") } ?: error("BuildInfo.VERSION not found")
        val found = Regex("\"([^\"]+)\"").find(line)?.groupValues?.get(1)
        check(found == expected) { "BuildInfo.VERSION = $found but project.version = $expected" }
    }
}
tasks.named("check") { dependsOn(checkVersion) }

tasks.named<ProcessResources>("jvmProcessResources") {
    val sdkVersion = libs.versions.janzeerSdk.get()
    inputs.property("sdkVersion", sdkVersion)
    filesMatching("jz-build.properties") { expand("sdkVersion" to sdkVersion) }
}
