plugins {
    `java-library`
    `maven-publish`
}

group = property("group") as String
version = property("version") as String

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("com.github.retrooper:packetevents-api:${property("packetEventsVersion")}")
    compileOnly("net.kyori:adventure-api:4.26.1")
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("com.github.retrooper:packetevents-api:${property("packetEventsVersion")}")
    testImplementation("net.kyori:adventure-api:4.26.1")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
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
