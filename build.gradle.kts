plugins {
    `java-library`
}

group = "io.github.dualuse"
version = "0.1.0"

repositories {
    mavenCentral()
}

// The jmh source set holds the benchmarks; it sees main but ships nowhere.
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val jmhImplementation: Configuration by configurations.getting
val jmhAnnotationProcessor: Configuration by configurations.getting

// The jcstress source set holds the concurrency tests of the lane state and the commit protocol.
val jcstress: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val jcstressImplementation: Configuration by configurations.getting
val jcstressAnnotationProcessor: Configuration by configurations.getting

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jmhImplementation("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    jcstressImplementation("org.openjdk.jcstress:jcstress-core:0.16")
    jcstressAnnotationProcessor("org.openjdk.jcstress:jcstress-core:0.16")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.encoding = "UTF-8"
}

// -PtestJdk=11 runs the tests on that JDK (Gradle itself needs 17+).
val testJdk = providers.gradleProperty("testJdk")

tasks.test {
    useJUnitPlatform()
    // the format's test vectors, shared with the Python and JavaScript tests
    val testdata = layout.projectDirectory.dir("testdata")
    inputs.dir(testdata).withPropertyName("testdata")
    systemProperty("tempo.testdata", testdata.asFile.absolutePath)
    if (testJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJdk.get())
        }
    }
}

// ./gradlew jmh                                  all benchmarks, with -prof gc
// ./gradlew jmh -Pjmh.args="Tempo -f 1 -prof gc" explicit JMH command line
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    args = (providers.gradleProperty("jmh.args").orNull ?: "-prof gc").trim().split(Regex("\\s+"))
}

// ./gradlew jmhGate [-PtestJdk=11]   the CI allocation gate (AllocationGate), about one minute
tasks.register<JavaExec>("jmhGate") {
    group = "verification"
    description = "Runs the allocation-gated benchmarks and fails above their gc.alloc.rate.norm gates."
    classpath = jmh.runtimeClasspath
    mainClass = "io.github.dualuse.tempo.AllocationGate"
    if (testJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJdk.get())
        }
    }
}

// ./gradlew jcstress                                   the jcstress tests, -m quick
// ./gradlew jcstress -Pjcstress.args="-m default"     explicit jcstress command line
tasks.register<JavaExec>("jcstress") {
    group = "verification"
    description = "Runs the jcstress tests of the lane state and the commit protocol."
    classpath = jcstress.runtimeClasspath
    mainClass = "org.openjdk.jcstress.Main"
    args = (providers.gradleProperty("jcstress.args").orNull ?: "-m quick").trim().split(Regex("\\s+"))
    workingDir = layout.buildDirectory.dir("jcstress").get().asFile
    doFirst { workingDir.mkdirs() }
    if (testJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJdk.get())
        }
    }
}

tasks.check {
    dependsOn(tasks.named("jmhClasses"), tasks.named("jcstressClasses"))
}
