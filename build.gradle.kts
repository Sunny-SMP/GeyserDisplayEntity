plugins {
    id("java")
    id("com.gradleup.shadow") version "9.6.1"
}

group = "net.sunnysmp"
version = "1.0.14"

repositories {
    mavenCentral()

    maven("https://repo.sunnyinfra.cloud/development/") {
        name = "SunnyInfra"
        credentials(PasswordCredentials::class)
    }
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("net.sunnysmp.geyser:core:2.12.0-SNAPSHOT")
    compileOnly("net.sunnysmp.geyser:api:2.12.0-SNAPSHOT")

    implementation("org.spongepowered:configurate-yaml:4.2.0-GeyserMC-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveFileName.set("${rootProject.name}-${version}.jar")

    relocate("org.spongepowered.configurate", "me.geyserextensionists.geyserdisplayentity.libs.configurate")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("extension.yml") {
        expand(props)
    }
}