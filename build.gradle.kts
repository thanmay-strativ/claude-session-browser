import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "com.mahadi"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against IntelliJ IDEA Community; the plugin only uses platform +
        // terminal APIs, so it also loads in PyCharm (Community and Professional).
        create("IC", "2025.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "299.*"
        }
    }
}

// The MCP server ships inside the plugin jar so a shared build is self-contained.
// Sources stay in mcp-server/ and are copied in at build time — one source of truth.
val bundleMcpServer by tasks.registering(Copy::class) {
    val outputDir = layout.buildDirectory.dir("generated-resources/mcp-server")
    from("mcp-server/pyproject.toml")
    from("mcp-server/src/claude_session_cache") {
        into("src/claude_session_cache")
        include("**/*.py")
    }
    exclude("**/__pycache__/**")
    into(outputDir)

    // Copy leaves stale files behind, which would ship a second, outdated copy of the
    // package alongside the current one.
    doFirst { delete(outputDir) }

    doLast {
        val root = outputDir.get().asFile
        val entries = root.walkTopDown()
            .filter { it.isFile && it.name != "MANIFEST.txt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        File(root, "MANIFEST.txt").writeText(entries.joinToString("\n"))
    }
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

tasks.named("processResources") { dependsOn(bundleMcpServer) }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
