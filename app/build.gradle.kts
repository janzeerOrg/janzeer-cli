plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.graalvm.native)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":jz-core"))
}

application {
    mainClass.set("org.janzeer.jz.MainKt")
    applicationName = "jz"
}

// Runnable jar: java -jar jz-<version>.jar … (the JVM fallback where no native binary is shipped).
val fatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("jz")
    archiveClassifier.set("")
    manifest { attributes("Main-Class" to "org.janzeer.jz.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
}

// Native binary: JAVA_HOME = GraalVM 17 (release/build-cli.sh sets it); ./gradlew :jz-app:nativeCompile → app/build/native/nativeCompile/jz
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("jz")
            mainClass.set("org.janzeer.jz.MainKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("--install-exit-handlers")
            buildArgs.add("-march=compatibility")
            buildArgs.add("-J-Xmx${project.findProperty("janzeer.nativeXmx") ?: "4g"}")
        }
    }
    agent {
        defaultMode.set("standard")
        metadataCopy {
            inputTaskNames.add("run")
            outputDirectories.add("src/main/resources/META-INF/native-image/org.janzeer/jz-agent")
            mergeWithExisting.set(true)
        }
    }
}
