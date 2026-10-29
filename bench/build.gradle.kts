import net.ltgt.gradle.errorprone.ErrorProneOptions

description = "JMH engine benchmarks and the generator for docs/results.md."

dependencies {
    implementation(project(":engine"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.withType<JavaCompile>().configureEach {
    // JMH generates harness classes; lint only hand-written code.
    (options as ExtensionAware).extensions.configure<ErrorProneOptions> {
        excludedPaths.set(".*/build/generated/.*")
    }
}

val quick = project.hasProperty("quick")

val benchReport by tasks.registering(JavaExec::class) {
    group = "benchmark"
    description = "Runs the JMH engine benchmarks and rewrites the engine section of docs/results.md."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.kaee.minikv.bench.EngineBenchReport")
    workingDir = rootProject.projectDir
    // -Pquick is a smoke run of the whole pipeline. Its numbers are not meaningful, so it writes
    // to build/ instead of docs/.
    val output = if (quick) "build/results-quick.md" else "docs/results.md"
    val command = if (quick) "./gradlew benchReport -Pquick" else "./gradlew benchReport"
    args(rootProject.file(output).absolutePath, command)
    if (quick) {
        systemProperty("minikv.bench.quick", "true")
    }
}
