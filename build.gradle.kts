plugins {
    java
}

group = "com.github.ysbbbbbb"
version = providers.gradleProperty("plugin_version").get()

val customCropsVersion = providers.gradleProperty("custom_crops_version")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paper_version").get()}")
    compileOnly("net.momirealms:craft-engine-core:${providers.gradleProperty("craft_engine_version").get()}")
    compileOnly("net.momirealms:craft-engine-bukkit:${providers.gradleProperty("craft_engine_version").get()}")
    compileOnly("net.momirealms:custom-crops:${customCropsVersion.get()}")
    // CraftEngine's custom BlockBehavior hooks expose NMS arguments through
    // this companion module. It is supplied by CraftEngine at runtime.
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:${providers.gradleProperty("craft_engine_proxy_version").get()}")

    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.momirealms:craft-engine-core:${providers.gradleProperty("craft_engine_version").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/paper/java"))
        resources.setSrcDirs(listOf("src/paper/resources"))
    }
    test {
        java.setSrcDirs(listOf("src/paperTest/java"))
        resources.setSrcDirs(listOf("src/paperTest/resources"))
    }
}

tasks.processResources {
    val replacements = mapOf("version" to project.version.toString())
    inputs.properties(replacements)
    filesMatching("plugin.yml") {
        expand(replacements)
    }

    from("src/paper/pack") {
        into("tavern-pack")
    }
    from("src/paper/customcrops") {
        into("customcrops")
    }
    from("src/main/resources") {
        include("assets/**")
        into("tavern-pack/resourcepack")
    }
    from("src/generated/resources") {
        include("assets/**")
        exclude(".cache/**")
        into("tavern-pack/resourcepack")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:all")
}

tasks.test {
    useJUnitPlatform()
    // Keep local verification viable beside a running game server and make
    // CI memory use deterministic. These tests are small, pure semantic
    // checks and do not need Gradle's much larger default worker heap.
    maxHeapSize = "128m"
    maxParallelForks = 1
    jvmArgs("-XX:MaxMetaspaceSize=128m")
}

tasks.jar {
    archiveBaseName = "KaleidoscopeTavern-Paper"
    archiveClassifier = ""
    manifest {
        attributes(
            "Implementation-Title" to "Kaleidoscope Tavern",
            "Implementation-Version" to project.version,
            "Required-CustomCrops-Version" to customCropsVersion.get()
        )
    }
}

val deployableJar = tasks.jar.flatMap { it.archiveFile }
val verifyPluginJar = tasks.register<Exec>("verifyPluginJar") {
    group = "verification"
    description = "Checks the deployable JAR, embedded CraftEngine project and CustomCrops content pack."
    dependsOn(tasks.jar)
    inputs.file(deployableJar)
    workingDir = projectDir
    commandLine(
        "python",
        "tools/verify_plugin_jar.py",
        deployableJar.get().asFile.absolutePath,
        customCropsVersion.get()
    )
}

tasks.register<Exec>("migrateLegacyContent") {
    group = "kaleidoscope tavern"
    description = "Regenerates the CraftEngine pack and runtime recipe catalog from the archived Forge resources."
    workingDir = projectDir
    commandLine("python", "tools/migrate_legacy.py")
}

tasks.register<Exec>("validatePack") {
    group = "verification"
    description = "Validates all generated CraftEngine definitions, recipes, models and runtime catalogs."
    workingDir = projectDir
    commandLine("python", "tools/validate_pack.py")
}

tasks.named("check") {
    dependsOn("validatePack", verifyPluginJar)
}
