import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import com.github.spotbugs.snom.SpotBugsTask
import java.math.BigDecimal
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    java
    id("checkstyle")
    id("com.diffplug.spotless") version "6.25.0"
    id("com.github.spotbugs") version "6.2.4"
    jacoco
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}

/** Testing, Gradle 9 compatible */
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(platform("org.junit:junit-bom:5.10.0"))
                implementation("org.junit.jupiter:junit-jupiter")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
                implementation("org.mockito:mockito-core:5.14.2")
                implementation("org.mockito:mockito-junit-jupiter:5.14.2")
            }
        }
    }
}

/** ---------- Jacoco: распаковать ВНУТРЕННИЙ агент и прокинуть путь второму JVM ---------- */

// 1) Копируем ВНУТРЕННИЙ jacocoagent.jar в build/jacoco/
val unpackJacocoAgent by tasks.register<Copy>("unpackJacocoAgent") {
    val agentZip = configurations.getByName("jacocoAgent").singleFile
    from(zipTree(agentZip)) { include("jacocoagent.jar") }
    into(layout.buildDirectory.dir("jacoco"))
    rename { "jacocoagent.jar" }
}

// 2) Перед запуском тестов:
//    - путь к внутреннему агенту: build/jacoco/jacocoagent.jar
//    - отдельный файл покрытия для дочернего JVM: build/jacoco/jacoco-it.exec
tasks.test {
    dependsOn(unpackJacocoAgent)
    useJUnitPlatform()

    doFirst {
        val agentPath = layout.buildDirectory.file("jacoco/jacocoagent.jar").get().asFile.absolutePath
        val itExec    = layout.buildDirectory.file("jacoco/jacoco-it.exec").get().asFile.absolutePath

        systemProperty("jacoco.agent.path", agentPath)
        systemProperty("jacoco.agent.destfile", itExec)
    }
}

// Чтобы отчёт всегда собирался после тестов
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
    systemProperty("file.encoding", "UTF-8")
    finalizedBy(tasks.jacocoTestReport)
}

/** Checkstyle */
tasks.withType<Checkstyle> {
    reports {
        xml.required.set(false)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/checkstyle/${name}.html"))
    }
}
checkstyle {
    toolVersion = "10.17.0"
    config = resources.text.fromFile("config/checkstyle/google_checks.xml")
    isShowViolations = true
    maxWarnings = 0
}

/** SpotBugs — как было */
spotbugs {
    ignoreFailures = false
}

tasks.spotbugsMain {
    reports.create("html") {
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/main.html").get().asFile
    }
}

tasks.spotbugsTest {
    reports.create("html") {
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/test.html").get().asFile
    }
}

/** Spotless */
spotless {
    java {
        googleJavaFormat("1.22.0")
        target("src/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

/** Jacoco report — the only block */
tasks.jacocoTestReport {
    dependsOn(tasks.test)

    // соберём ВСЕ exec/ec, включая jacoco-it.exec
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            //executionData.setFrom(fileTree(layout.buildDirectory.asFile.get()) {
            include("**/*.exec", "**/*.ec")
        })

    classDirectories.setFrom(files(sourceSets.main.get().output))
    sourceDirectories.setFrom(files(sourceSets.main.get().allSource.srcDirs))

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

/** All checks */
//tasks.named("check") {
//    dependsOn("spotlessCheck", "spotbugsMain", "spotbugsTest")
//}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// checking coverage threshold: fail, if < 50% by lines
val jacocoCoverage by tasks.register<JacocoCoverageVerification>("jacocoCoverage") {
    dependsOn(tasks.test)

    // Берём все .exec/.ec, включая твой jacoco-it.exec
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("**/*.exec", "**/*.ec")
        }
    )
    classDirectories.setFrom(files(sourceSets.main.get().output))
    sourceDirectories.setFrom(files(sourceSets.main.get().allSource.srcDirs))

    violationRules {
        rule {
            // we can change counter to INSTRUCTION/BRANCH if neeeded
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = BigDecimal("0.50")
            }
        }
    }
}

// all checks,  report and verification
tasks.named("check") {
    dependsOn(
        "spotlessCheck",
        "spotbugsMain",
        "spotbugsTest",
        tasks.jacocoTestReport, //compile Jococo report
        jacocoCoverage          // fail, if coverage is less than < 50% by lines
    )
}