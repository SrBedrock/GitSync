plugins {
    id("java")
}

group = "me.vrganj"
version = "1.3.0-SNAPSHOT"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.13.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
