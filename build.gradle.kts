plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.globalflashback"
version = "0.1.0-SNAPSHOT-phase1"
description = "Global Flashback Recorder �?Phase 1 POC"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
}

tasks {
    compileJava {
        options.release.set(25)
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("")
    }

    runServer {
        minecraftVersion("26.2")
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }
}

