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
