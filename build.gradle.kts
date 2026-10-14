plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2022.1.3") {
            useInstaller = false
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Eklenti yalnızca Java PSI + platform çekirdek API'lerini kullanıyor.
        // com.intellij.modules.json (Jackson yeterli) ve com.intellij.database (kullanılmıyor)
        // bağımlılıkları kaldırıldı; bunlar IDEA Community'de bundled değildir ve
        // plugin descriptor yüklenirken "Fail to load plugin descriptor" hatasına yol açıyordu.
        // IDEA 2022.1 is still monolithic; these core jars are not exposed by the
        // 2.x Gradle plugin classpath for 221, so add them explicitly for compile.
        bundledLibrary("lib/app.jar")
        bundledLibrary("lib/util.jar")
        bundledLibrary("lib/util_rt.jar")
        bundledPlugin("com.intellij.java")
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    // instrumentCode, ortamdaki JDK yolu sorunu nedeniyle devre disi; eklenti islevi icin gerekli degil.
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "221"
            // untilBuild açık bırakıldı ki ileri sürüm IDE'lerde de eklenti uyumlu kabul edilsin.
            untilBuild = provider { null }
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

tasks.register<JavaExec>("verifyPetstoreParser") {
    group = "verification"
    description = "Petstore Swagger 2.0 JSON ile SwaggerParser'ı doğrular"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.example.ykplugin.parser.PetstoreVerificationKt")
    val java21 = file("${System.getProperty("user.home")}/.jdks/ms-21.0.11/bin/java.exe")
    if (java21.exists()) setExecutable(java21.absolutePath)
}
