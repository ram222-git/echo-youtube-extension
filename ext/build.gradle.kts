plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.gradle.shadow)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

fun <T : ModuleDependency> T.excludeKotlin() {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
}

dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)

    // Android framework (headless WebView for the on-device PO Token minter).
    // Provided at runtime by the host app process; compile-only so it is never
    // bundled into the extension jar.
    val androidSdkDir = file(
        System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"
    )
    val androidJar = androidSdkDir.resolve("platforms").listFiles()
        ?.filter { it.name.startsWith("android-") && it.resolve("android.jar").isFile }
        ?.maxByOrNull { it.name.removePrefix("android-").toDoubleOrNull() ?: 0.0 }
        ?.resolve("android.jar")
    require(androidJar != null) {
        "Android SDK platform not found under ${androidSdkDir}/platforms - needed to compile the on-device PO Token minter (android.webkit.WebView)"
    }
    compileOnly(files(androidJar))

    api(libs.ytmkt) {
        excludeKotlin()
    }
    implementation(libs.newpipe) { excludeKotlin() }
    implementation(libs.rhino) { excludeKotlin() }
    implementation(libs.ktor.client.core) { excludeKotlin() }
    implementation(libs.ktor.client.cio) { excludeKotlin() }
    implementation(libs.ktor.client.content.negotiation) { excludeKotlin() }
    implementation(libs.ktor.serialization.kotlinx.json) { excludeKotlin() }

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)
}

// JUnit 4 (vintage style) — without this Gradle 9 discovers no tests at all.
tasks.withType<Test>().configureEach {
    useJUnit()
}


// Extension properties goto `gradle.properties` to set values

val extType: String by project
val extId: String by project
val extClass: String by project

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toInt()
val verCode = gitCount
val verName = "v$gitHash"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = verName

            from(components["java"])
        }
    }
}

tasks {
    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(verName)
        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,

                    "Extension-Version-Code" to verCode,
                    "Extension-Version-Name" to verName,

                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,

                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,

                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl
                )
            )
        }
    }
}


fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()