plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.oneblock"
version = "1.0.0"
description = "Island-based chest shops with multi-currency support"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()

    // Paper
    maven("https://repo.papermc.io/repository/maven-public/")

    // Vault
    maven("https://jitpack.io")

    // SuperiorSkyblock2
    maven("https://repo.bg-software.com/repository/api/")

    // WillFP / Eco
    maven("https://repo.auxilor.io/repository/maven-public/")
}

dependencies {
    // Paper API — provided at runtime by the server
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Vault — provided at runtime
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    // SuperiorSkyblock2 — provided at runtime
    compileOnly("com.bgsoftware:SuperiorSkyblockAPI:2025.2")

    // Eco (EcoBits dependency) — provided at runtime
    compileOnly("com.willfp:eco:6.77.2")

    // HikariCP — shaded into the jar
    implementation("com.zaxxer:HikariCP:5.1.0")

    // MariaDB JDBC driver — shaded into the jar
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")
}

tasks {
    // Ensure UTF-8 encoding and Java 21 target
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // Process plugin.yml and config.yml (replaces ${version} etc.)
    processResources {
        from("src/main/resources")
    }

    // Shadow jar — bundle HikariCP + MariaDB driver, relocate to avoid conflicts
    shadowJar {
        archiveClassifier.set("") // output: oneblock-shops-1.0.0.jar (no "-all" suffix)

        relocate("com.zaxxer.hikari", "com.oneblock.shops.libs.hikari")
        relocate("org.mariadb.jdbc",  "com.oneblock.shops.libs.mariadb")

        // Exclude signature files that cause issues when shading
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    // Make the default 'build' task produce the shaded jar
    build {
        dependsOn(shadowJar)
    }

    // Suppress the plain jar — we only want the shaded one
    jar {
        enabled = false
    }
}
