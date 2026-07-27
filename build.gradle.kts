plugins {
    `java-library`
    `maven-publish`
}

val codegen = sourceSets.create("codegen")
val integrationPlugin = sourceSets.create("integrationPlugin") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}
val legacyIntegrationPlugin = sourceSets.create("legacyIntegrationPlugin") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}
configurations.named(integrationPlugin.compileClasspathConfigurationName) {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
configurations.named(legacyIntegrationPlugin.compileClasspathConfigurationName) {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

group = property("group") as String
version = property("version") as String

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.github.retrooper:packetevents-api:${property("packetEventsVersion")}")
    compileOnly("net.kyori:adventure-api:4.26.1")
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("com.github.retrooper:packetevents-api:${property("packetEventsVersion")}")
    testImplementation("com.github.retrooper:packetevents-netty-common:${property("packetEventsVersion")}")
    testImplementation("io.netty:netty-all:4.1.72.Final")
    testImplementation("net.kyori:adventure-api:4.26.1")
    testImplementation("net.kyori:adventure-nbt:4.26.1")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(codegen.implementationConfigurationName, "com.google.code.gson:gson:2.13.1")
    add(integrationPlugin.compileOnlyConfigurationName, "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    add(integrationPlugin.compileOnlyConfigurationName, "com.github.retrooper:packetevents-api:${property("packetEventsVersion")}")
    add(legacyIntegrationPlugin.compileOnlyConfigurationName, "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    add(legacyIntegrationPlugin.compileOnlyConfigurationName, "com.github.retrooper:packetevents-api:${property("packetEventsVersion")}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.named<JavaCompile>(integrationPlugin.compileJavaTaskName) {
    options.release = 21
}

tasks.named<JavaCompile>(legacyIntegrationPlugin.compileJavaTaskName) {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}

val generatedMetadataKeys = layout.projectDirectory.file(
    "src/main/java/io/github/twme/virtualentities/metadata/GeneratedEntityMetadataKeys.java"
)

tasks.register<JavaExec>("generateMetadataKeys") {
    group = "code generation"
    description = "Generates typed metadata keys from bundled entity-data."
    classpath = codegen.runtimeClasspath
    mainClass = "io.github.twme.virtualentities.codegen.MetadataKeysGenerator"
    args(
        layout.projectDirectory.dir("src/main/resources/entity-data").asFile.absolutePath,
        generatedMetadataKeys.asFile.absolutePath
    )
}

val verifyGeneratedMetadataKeys = tasks.register<JavaExec>("verifyGeneratedMetadataKeys") {
    group = "verification"
    description = "Checks that typed metadata keys match bundled entity-data."
    classpath = codegen.runtimeClasspath
    mainClass = "io.github.twme.virtualentities.codegen.MetadataKeysGenerator"
    args(
        layout.projectDirectory.dir("src/main/resources/entity-data").asFile.absolutePath,
        generatedMetadataKeys.asFile.absolutePath,
        "--check"
    )
}

val verifyEntityData = tasks.register<Exec>("verifyEntityData") {
    group = "verification"
    description = "Validates every bundled entity-data snapshot and reviewed semantic flag manifest."
    commandLine(
        "node",
        layout.projectDirectory.file("tools/verify-entity-data.mjs").asFile.absolutePath,
        layout.projectDirectory.dir("src/main/resources/entity-data").asFile.absolutePath,
        layout.projectDirectory.file("data/metadata-flags/semantic-flags.json").asFile.absolutePath
    )
}

tasks.check {
    dependsOn(verifyGeneratedMetadataKeys)
    dependsOn(verifyEntityData)
}

val integrationPluginVersion = version.toString()
tasks.named<ProcessResources>(integrationPlugin.processResourcesTaskName) {
    filesMatching("plugin.yml") {
        expand("version" to integrationPluginVersion)
    }
}
tasks.named<ProcessResources>(legacyIntegrationPlugin.processResourcesTaskName) {
    filesMatching("plugin.yml") {
        expand("version" to integrationPluginVersion)
    }
}

tasks.register<Jar>("integrationPluginJar") {
    group = "verification"
    description = "Builds the Paper plugin used by the Mineflayer black-box test."
    archiveBaseName = "VirtualEntitiesIntegration"
    archiveVersion = ""
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(integrationPlugin.output)
}

tasks.register<Jar>("legacyIntegrationPluginJar") {
    group = "verification"
    description = "Builds the Java 17 plugin used by legacy Paper and Mineflayer tests."
    archiveBaseName = "VirtualEntitiesLegacyIntegration"
    archiveVersion = ""
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(legacyIntegrationPlugin.output)
}

tasks.register<Exec>("mineflayerE2e") {
    group = "verification"
    description = "Runs the Paper and Mineflayer black-box entity test."
    dependsOn("integrationPluginJar")
    commandLine("./integration/mineflayer/run-e2e.sh")
}

tasks.register<Exec>("legacyMineflayerE2e") {
    group = "verification"
    description = "Runs Java 17 legacy Paper and Mineflayer compatibility tests."
    dependsOn("legacyIntegrationPluginJar")
    commandLine("./integration/mineflayer/run-legacy-e2e.sh")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "VirtualEntities"
                description = "Version-aware virtual Minecraft entities powered by PacketEvents."
                url = "https://github.com/twme-ai/VirtualEntities"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/twme-ai/VirtualEntities.git"
                    developerConnection = "scm:git:ssh://github.com/twme-ai/VirtualEntities.git"
                    url = "https://github.com/twme-ai/VirtualEntities"
                }
            }
        }
    }
}
