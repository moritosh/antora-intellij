import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.moritosh.antora"
version = providers.gradleProperty("pluginVersion").get()

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.reader().use(::load)
}
fun localSetting(name: String): String? = localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull
val platformLocalPath = localSetting("platformLocalPath")
val asciidocLocalPath = localSetting("asciidocLocalPath")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (platformLocalPath != null) local(platformLocalPath)
        else intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.modules.jcef")
        if (asciidocLocalPath != null) localPlugin(file(asciidocLocalPath))
        else plugin("org.asciidoctor.intellij.asciidoc", providers.gradleProperty("asciidocPluginVersion").get())
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Java 21 bytecode, which every supported platform runs (2026.2 is on Java 25).
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    // For publishPlugin; unsigned unless the signing variables are set.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// The preview server is the same script antora-ls runs for Zed; bundle it as a resource.
val previewScript = providers.gradleProperty("antoraPreviewScript").map { rootProject.file(it) }
val previewResources = layout.buildDirectory.dir("generated/preview-resources")
val copyPreviewScript = tasks.register<Copy>("copyPreviewScript") {
    from(previewScript)
    into(previewResources.map { it.dir("antora-preview") })
}
sourceSets.main {
    resources.srcDir(files(previewResources).builtBy(copyPreviewScript))
}
tasks.processResources {
    val script = previewScript
    doFirst {
        check(script.get().isFile) {
            "antora-preview.js not found at ${script.get()}; set antoraPreviewScript in gradle.properties"
        }
    }
}
