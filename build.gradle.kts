plugins {
    `java-library`
}

group = "io.github.raipc"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(platform("io.opentelemetry:opentelemetry-bom:1.60.1"))
    api("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
