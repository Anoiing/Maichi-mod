import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions

plugins {
    id("com.android.application") version "9.0.1"
}

android {
    namespace = "com.maichimod.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maichimod.prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            providers.gradleProperty("maichiFixtureDir").orNull?.let { path ->
                it.systemProperty("maichi.fixtureDir", rootProject.file(path).absolutePath)
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val localDebugAssets = layout.projectDirectory.dir("src/debug/assets/maichi-local").asFile.toPath()
val buildDirectory = layout.buildDirectory
val privateDirectoryMode = PosixFilePermissions.fromString("rwx------")
val privateFileMode = PosixFilePermissions.fromString("rw-------")
val supportsPosixPermissions = Files.getFileStore(layout.projectDirectory.asFile.toPath())
    .supportsFileAttributeView("posix")

val protectDebugInputs = tasks.register("protectDebugInputs") {
    doLast {
        if (!supportsPosixPermissions) return@doLast
        // Protect copied and compressed assets before AGP creates them.
        val buildPath = buildDirectory.get().asFile.toPath()
        Files.createDirectories(buildPath, PosixFilePermissions.asFileAttribute(privateDirectoryMode))
        Files.setPosixFilePermissions(buildPath, privateDirectoryMode)
        if (Files.isDirectory(localDebugAssets, NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(localDebugAssets, privateDirectoryMode)
            Files.list(localDebugAssets).use { files ->
                files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .forEach { Files.setPosixFilePermissions(it, privateFileMode) }
            }
        }
    }
}

val protectDebugApk = tasks.register("protectDebugApk") {
    doLast {
        if (!supportsPosixPermissions) return@doLast
        val output = buildDirectory.dir("outputs/apk/debug").get().asFile.toPath()
        if (!Files.isDirectory(output, NOFOLLOW_LINKS)) return@doLast
        Files.list(output).use { files ->
            files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .filter { it.fileName.toString().endsWith(".apk") || it.fileName.toString().endsWith(".idsig") }
                .forEach { Files.setPosixFilePermissions(it, privateFileMode) }
        }
    }
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(protectDebugInputs)
}
tasks.matching { it.name == "packageDebug" }.configureEach {
    finalizedBy(protectDebugApk)
}
tasks.matching { it.name in setOf("mergeDebugAssets", "compressDebugAssets", "packageDebug") }.configureEach {
    outputs.doNotCacheIf("Local debug assets can contain captured video") { Files.isDirectory(localDebugAssets) }
}
