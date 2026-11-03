import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Built with JDK 21 but emits Java 17 bytecode, so the Android app (Java 17) can consume it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

// gradle :core:rebuildFit -Pride=<ride folder or gnss.csv> -Pout=<output.fit> [-Pshift=<milliseconds>]
tasks.register<JavaExec>("rebuildFit") {
    group = "tools"
    description = "Rebuild a ride's .fit from its gnss.csv"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.nanodash.core.tools.RebuildFitKt")
    doFirst {
        val shift = project.findProperty("shift")?.toString()
        args(listOfNotNull(project.findProperty("ride")?.toString() ?: "", project.findProperty("out")?.toString() ?: "", shift))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // Lets the FIT test leave its sample file where tools/validate_fit.py can check it with independent decoders.
    systemProperty("fit.samples.dir", layout.buildDirectory.dir("fit-samples").get().asFile.absolutePath)
}
