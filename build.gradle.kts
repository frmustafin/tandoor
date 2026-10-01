plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dubrava"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")

    // Telegram adapter: RestClient without a servlet container, the bot uses long polling.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // Jackson 3 (tools.jackson): the generation Boot 4 RestClient actually uses.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Spring Data JDBC over Hibernate: flat schema, no object graphs, small heap.
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Boot 4 ships auto-configuration in per-technology modules; flyway-core alone stays inert.
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
