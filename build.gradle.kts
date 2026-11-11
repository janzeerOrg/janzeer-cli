plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.graalvm.native) apply false
}

allprojects {
    group = "org.janzeer"
    // Keep in step with BuildInfo.VERSION (core) — `./gradlew check` fails on a mismatch (see :jz-core:checkVersion).
    version = "0.1.1"
}
