plugins {
    java
    `java-library`
    kotlin("jvm") version KOTLIN_VERSION apply true
}

allprojects {
    group = "org.gradle.kotlin.dsl.samples.multiproject"

    version = "1.0"

    repositories {
        mavenCentral()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

allprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
}

repositories {
    mavenCentral()
    maven { setUrl("https://plugins.gradle.org/m2") }
}

val test by tasks.getting(Test::class) {
    useTestNG()
    // Tests live in the subprojects: don't fail when a --tests filter
    // matches nothing in this (empty) root project.
    filter {
        isFailOnNoMatchingTests = false
    }
}

dependencies {
    listOf("stdlib", "reflect").forEach {
        implementation(kotlin(it))
    }
    listOf("test").forEach {
        testImplementation(kotlin(it))
    }
    listOf("org.testng:testng:7.5.1", "org.assertj:assertj-core:3.10.0").forEach {
        testImplementation(it)
    }

    listOf("klaxon", "klaxon-jackson").forEach {
        implementation(project(":$it", "default"))
    }
}

//
//dependencies {
//    // Make the root project archives configuration depend on every subproject
//    subprojects.forEach {
//        archives(it)
//    }
//}

subprojects {
    extra["signing.secretKeyRingFile"] = System.getProperty("user.home") + "/.gnupg/secring.gpg"
}
