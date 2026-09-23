plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.globalflashback"
version = "0.1.0-SNAPSHOT"

/**
 * Build target Minecraft / Paper line.
 *   -Pgfr.mc=26.2      (default) Paper 26.2 — SPEC / AGENTS primary
 *   -Pgfr.mc=1.21.11   Paper 1.21.11 adaptation
 */
val gfrMc: String = (findProperty("gfr.mc") as String?)?.trim()?.ifEmpty { null } ?: "26.2"
val is12111 = gfrMc == "1.21.11"
val is262 = gfrMc == "26.2"

if (!is12111 && !is262) {
    throw GradleException("Unsupported gfr.mc='$gfrMc'. Use 26.2 or 1.21.11")
}

description = if (is12111) {
    "Global Flashback Recorder — Paper 1.21.11"
} else {
    "Global Flashback Recorder — Paper 26.2"
}

val javaRelease = if (is12111) 21 else 25

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaRelease))
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaRelease))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    if (is12111) {
        paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    } else {
        paperweight.paperDevBundle("26.2.build.+")
    }
}

tasks {
    compileJava {
        options.release.set(javaRelease)
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf(
            "version" to version,
            "apiVersion" to if (is12111) "1.21" else "26.2",
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("")
        archiveAppendix.set(if (is12111) "1.21.11" else "26.2")
    }

    runServer {
        minecraftVersion(if (is12111) "1.21.11" else "26.2")
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
}

// Only compile the NMS package matching the selected Paper line.
tasks.named<JavaCompile>("compileJava") {
    if (is12111) {
        exclude("**/nms/v26_2/**")
    } else {
        exclude("**/nms/v1_21_11/**")
    }
}

tasks.register("printGfrMc") {
    doLast {
        println("gfr.mc=$gfrMc javaRelease=$javaRelease")
    }
}

// paperweight taskCache/mappedServerJar.jar is shared; wipe when switching -Pgfr.mc
// so JDK 21 cannot read a leftover Java-25 (26.2) mapped jar (and vice versa).
val paperweightMcMarker = layout.projectDirectory.file(".gradle/caches/paperweight/gfr-mc.txt")
tasks.named("paperweightUserdevSetup") {
    doFirst {
        val marker = paperweightMcMarker.asFile
        val previous = marker.takeIf { it.exists() }?.readText()?.trim()
        if (previous != null && previous != gfrMc) {
            val taskCache = marker.parentFile.resolve("taskCache")
            if (taskCache.exists()) {
                taskCache.deleteRecursively()
                logger.lifecycle("Cleared paperweight taskCache (gfr.mc $previous → $gfrMc)")
            }
        }
        marker.parentFile.mkdirs()
        marker.writeText(gfrMc)
    }
}
