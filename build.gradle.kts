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
    // CraftEngine 26.8 is only published as a snapshot for now.
    maven("https://repo.momirealms.net/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.catnies.top/releases")
}

val buildTools = sourceSets.create("buildTools") {
    java.setSrcDirs(listOf("src/buildTools/java"))
    resources.setSrcDirs(emptyList<String>())
}

val embeddedLibraries = configurations.create("embeddedLibraries") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paper_version").get()}")
    compileOnly("net.momirealms:craft-engine-core:${providers.gradleProperty("craft_engine_version").get()}")
    compileOnly("net.momirealms:craft-engine-bukkit:${providers.gradleProperty("craft_engine_version").get()}")
    compileOnly("net.momirealms:custom-crops:${customCropsVersion.get()}")
    // CraftEngine's custom BlockBehavior hooks expose NMS arguments through
    // this companion module. It is supplied by CraftEngine at runtime.
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:${providers.gradleProperty("craft_engine_proxy_version").get()}")
    // The HUD placeholder for CustomNameplates and other PlaceholderAPI users.
    compileOnly("me.clip:placeholderapi:${providers.gradleProperty("placeholder_api_version").get()}")
    implementation("net.momirealms:sparrow-yaml:${providers.gradleProperty("sparrow_yaml_version").get()}")
    embeddedLibraries("net.momirealms:sparrow-yaml:${providers.gradleProperty("sparrow_yaml_version").get()}")

    add(buildTools.implementationConfigurationName, "com.google.code.gson:gson:2.13.2")

    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.momirealms:craft-engine-core:${providers.gradleProperty("craft_engine_version").get()}")
    // CraftEngine publishes its libraries as dependency-less poms; the runtime
    // server supplies them. Tests that model CE types (e.g. the station visual
    // diff state machine) need the companion libraries on the worker classpath.
    testRuntimeOnly("net.momirealms:craft-engine-adventure:${providers.gradleProperty("craft_engine_version").get()}")
    // MiniMessage round-trips in CustomEffectHudSemanticsTest use the same
    // adventure version Paper ships at runtime.
    testImplementation("io.papermc.paper:paper-api:${providers.gradleProperty("paper_version").get()}")
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

    from(listOf(
        "LICENSE-CODE",
        "LICENSE-ASSETS",
        "ASSET-CREDITS.md",
        "THIRD-PARTY-NOTICES.md"
    )) {
        into("META-INF")
    }
    from("THIRD-PARTY-LICENSES") {
        into("META-INF/third-party-licenses")
    }

    from("src/paper/pack") {
        into("tavern-pack")
    }
    from("src/paper/customcrops") {
        into("customcrops")
    }
    from("src/paper/customnameplates") {
        into("customnameplates")
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
    from(embeddedLibraries.map { library ->
        if (library.isDirectory) library else zipTree(library)
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

val deployableJar = tasks.jar.flatMap { it.archiveFile }

// Native Java build tools. The same classes are used by the migration and
// validation tasks below; Gson stays confined to the buildTools source set and
// never enters the deployable JAR.
val verifyPluginJar = tasks.register<JavaExec>("verifyPluginJar") {
    group = "verification"
    description = "Checks the deployable JAR, embedded CraftEngine project and CustomCrops content pack."
    dependsOn(tasks.jar, buildTools.classesTaskName)
    classpath = buildTools.runtimeClasspath
    mainClass.set("com.github.ysbbbbbb.kaleidoscopetavern.buildtools.PluginJarVerifier")
    workingDir(projectDir)
    args(deployableJar.get().asFile.absolutePath, customCropsVersion.get())
    inputs.file(deployableJar)
}

tasks.register<JavaExec>("migrateLegacyContent") {
    group = "kaleidoscope tavern"
    description = "Regenerates the CraftEngine pack and runtime recipe catalog from the archived Forge resources."
    dependsOn(buildTools.classesTaskName)
    classpath = buildTools.runtimeClasspath
    mainClass.set("com.github.ysbbbbbb.kaleidoscopetavern.buildtools.LegacyContentMigrator")
    workingDir(projectDir)
    args("--root", projectDir.absolutePath)
    inputs.files(
        fileTree("src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init"),
        fileTree("src/main/resources"),
        fileTree("src/generated/resources")
    )
    outputs.files(
        fileTree("src/paper/pack/configuration"),
        fileTree("src/paper/resources/catalog"),
        fileTree("src/paper/resources/recipes"),
        fileTree("src/paper/pack/resourcepack")
    )
}

tasks.register<JavaExec>("validatePack") {
    group = "verification"
    description = "Validates all generated CraftEngine definitions, recipes, models and runtime catalogs."
    dependsOn(buildTools.classesTaskName)
    classpath = buildTools.runtimeClasspath
    mainClass.set("com.github.ysbbbbbb.kaleidoscopetavern.buildtools.PackValidator")
    workingDir(projectDir)
    args(projectDir.absolutePath)
}

val validateServerStateBudget = tasks.register<JavaExec>("validateServerStateBudget") {
    group = "verification"
    description = "Guards the CraftEngine 2000-state pool with a 1000-state reserve for other projects."
    dependsOn(buildTools.classesTaskName)
    classpath = buildTools.runtimeClasspath
    mainClass.set("com.github.ysbbbbbb.kaleidoscopetavern.buildtools.ServerStateBudgetValidator")
    workingDir(projectDir)
    args("--capacity", "2000", "--reserve", "1000")
    inputs.file("src/paper/pack/configuration/blocks.json")
    inputs.property("capacity", 2000)
    inputs.property("reserve", 1000)
}

tasks.named("check") {
    dependsOn("validatePack", validateServerStateBudget, verifyPluginJar)
}
