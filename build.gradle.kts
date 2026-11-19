import java.io.ByteArrayOutputStream
import javax.inject.Inject

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

// The demo source set: small programs that use Tempo the way an application does, graded simple,
// medium and complex (src/demo/README.md). They see main, need nothing else and ship nowhere.
val demo: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// The demos are read as examples, so they compile without a single warning.
tasks.named<JavaCompile>(demo.compileJavaTaskName) {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// TempoJackson compiles against the Jackson 2.16 API; the library itself needs nothing.
val jackson = "2.16.1"

dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-databind:$jackson")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jackson")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jackson")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-smile:$jackson")
    testImplementation("org.msgpack:jackson-dataformat-msgpack:0.9.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jmhImplementation("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    jcstressImplementation("org.openjdk.jcstress:jcstress-core:0.16")
    jcstressAnnotationProcessor("org.openjdk.jcstress:jcstress-core:0.16")
}

// The Tempo-Link base baked into the jar (Tempo.Link), so a fork's jar links to the fork's viewer
// (.github/workflows/pages.yml deploys it there once Pages is on). First of:
//   -PlinkBase=URL                  any viewer, e.g. a copy hosted inside a company
//   GITHUB_REPOSITORY=owner/repo    as GitHub Actions sets it: https://<owner>.github.io/<repo>/
//   git remote origin on github.com the same, for builds outside GitHub Actions
//   https://dualuse.github.io/tempo/
// Options.linkBase and -Dtempo.linkBase override it at run time; a copy of Tempo.java without the
// resource uses the same literal (Tempo.Link.DEFAULT_LINK_BASE).
val upstreamLinkBase = "https://dualuse.github.io/tempo/"

/** The GitHub Pages site of owner/repo (owner lower-cased; owner.github.io is the site root); null for anything else. */
fun pagesOf(repository: String?): String? {
    val m = Regex("([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)").matchEntire(repository?.trim() ?: "") ?: return null
    val owner = m.groupValues[1].lowercase()
    val repo = m.groupValues[2]
    return if (repo.lowercase() == "$owner.github.io") "https://$owner.github.io/" else "https://$owner.github.io/$repo/"
}

/** owner/repo of a github.com remote URL (https, ssh or scp-like, with or without .git); null for anything else. */
fun repositoryOfRemote(url: String?): String? {
    val m = Regex("(?:(?:https?|ssh|git)://(?:[^@/]+@)?github\\.com(?::\\d+)?/|(?:[^@/]+@)?github\\.com:)([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?")
        .matchEntire(url?.trim() ?: "") ?: return null
    return m.groupValues[1] + "/" + m.groupValues[2]
}

fun linkBaseOf(property: String?, repository: String?, remote: String?): String {
    val p = property?.trim() ?: ""
    if (p.isNotEmpty()) {
        require(Regex("[A-Za-z][A-Za-z0-9+.-]*:[\\x21-\\x7e&&[^#,]]*").matches(p)) { "-PlinkBase=$p is not an absolute URL without '#' or ','" }
        return p
    }
    return pagesOf(repository) ?: pagesOf(repositoryOfRemote(remote)) ?: upstreamLinkBase
}

/** The URL of the git remote origin of the project directory; absent without git or a remote. */
abstract class GitOrigin : ValueSource<String, GitOrigin.Params> {
    interface Params : ValueSourceParameters {
        val dir: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String? = try {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine("git", "-C", parameters.dir.get().asFile.path, "config", "--get", "remote.origin.url")
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        out.toString().trim().takeIf { result.exitValue == 0 && it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

val linkBaseProperty = providers.gradleProperty("linkBase").orElse("")
val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY").orElse("")
val gitOrigin = providers.of(GitOrigin::class) { parameters.dir.set(layout.projectDirectory) }.orElse("")
val linkBase = linkBaseProperty.zip(githubRepository) { p, r -> p to r }.zip(gitOrigin) { (p, r), o -> linkBaseOf(p, r, o) }

val generateLinkBase by tasks.registering {
    description = "Writes the Tempo-Link base the library links to by default."
    val base = linkBase
    val dir = layout.buildDirectory.dir("generated/linkBase")
    inputs.property("linkBase", base)
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("io/github/dualuse/tempo/link-base.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(base.get() + "\n")
    }
}
sourceSets.main { resources.srcDir(generateLinkBase) }

tasks.jar {
    manifest.attributes(mapOf("Tempo-Link-Base" to linkBase))
}

// ./gradlew checkLinkBase   the rule above, for a few cases (part of check)
val checkLinkBase by tasks.registering {
    group = "verification"
    description = "Checks how the Tempo-Link base is derived from -PlinkBase, GITHUB_REPOSITORY and the git remote."
    val cases = listOf(
        Triple(null, null, null) to upstreamLinkBase,
        Triple("", "", "") to upstreamLinkBase,
        Triple(null, "not a repository", "/some/local/path") to upstreamLinkBase,
        Triple(null, "dualuse/tempo", null) to "https://dualuse.github.io/tempo/",
        Triple(null, "Some-Owner/tempo-fork", "git@github.com:dualuse/tempo.git") to "https://some-owner.github.io/tempo-fork/",
        Triple(null, "someone/Someone.github.io", null) to "https://someone.github.io/",
        Triple(null, "", "git@github.com:Some-Owner/tempo-fork.git") to "https://some-owner.github.io/tempo-fork/",
        Triple(null, "", "https://github.com/Some-Owner/tempo-fork") to "https://some-owner.github.io/tempo-fork/",
        Triple(null, "", "https://token@github.com/some-owner/tempo.fork.git/") to "https://some-owner.github.io/tempo.fork/",
        Triple(null, "", "ssh://git@github.com:22/some-owner/tempo-fork.git") to "https://some-owner.github.io/tempo-fork/",
        Triple(null, "", "git@gitlab.com:some-owner/tempo-fork.git") to upstreamLinkBase,
        Triple("https://tempo.example.org/viewer/", "Some-Owner/tempo-fork", null) to "https://tempo.example.org/viewer/",
    ).map { (input, expected) -> Triple(input, expected, linkBaseOf(input.first, input.second, input.third)) }
    doLast {
        val wrong = cases.filter { it.second != it.third }
        check(wrong.isEmpty()) { wrong.joinToString("\n") { "${it.first}: ${it.third}, expected ${it.second}" } }
    }
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
    // LinkTest derives the baked base from these on its own
    inputs.property("linkBaseInputs", linkBaseProperty.zip(githubRepository) { p, r -> "$p $r" }.zip(gitOrigin) { a, o -> "$a $o" })
    systemProperty("tempo.linkBaseProperty", linkBaseProperty.get())
    systemProperty("tempo.githubRepository", githubRepository.get())
    systemProperty("tempo.gitOrigin", gitOrigin.get())
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

// ./gradlew jmhGate [-PtestJdk=11]   the CI cost gate (CostGate): allocation and the four cost promises, about four minutes
tasks.register<JavaExec>("jmhGate") {
    group = "verification"
    description = "Runs the gated benchmarks and fails when one breaks a cost promise or an allocation gate."
    classpath = jmh.runtimeClasspath
    mainClass = "io.github.dualuse.tempo.CostGate"
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

// ./gradlew demo                              lists the demos
// ./gradlew -q demo -Pdemo=FirstTrace         runs one: its story, then its response headers
// ./gradlew -q demo -Pdemo=FirstTrace | tools/tempo   the same, drawn as lanes in the terminal
// ./gradlew demos [-PtestJdk=11]              runs every demo and checks what each one claims
tasks.register<JavaExec>("demo") {
    group = "demo"
    description = "Lists the demos, or runs the one named by -Pdemo=Name."
    classpath = demo.runtimeClasspath
    mainClass = "io.github.dualuse.tempo.demo.Demos"
    args(listOfNotNull(providers.gradleProperty("demo").orNull))
}

val demos by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs every demo and fails if one of them does not show what it claims."
    classpath = demo.runtimeClasspath
    mainClass = "io.github.dualuse.tempo.demo.Demos"
    args("--all")
    if (testJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJdk.get())
        }
    }
}

tasks.check {
    dependsOn(tasks.named("jmhClasses"), tasks.named("jcstressClasses"), checkLinkBase, demos)
}
