description = "LSM-tree storage engine. No networking and no runtime dependencies."

dependencies {
    testImplementation("net.jqwik:jqwik:1.9.2")
}

tasks.withType<JavaCompile>().configureEach {
    // jqwik reads parameter names for reporting.
    options.compilerArgs.add("-parameters")
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("crash") }
}

val crashTest by tasks.registering(Test::class) {
    description = "Crash recovery tests: SIGKILLed writer processes and torn WAL tails."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("crash") }
    // The crash test starts child JVMs that need the full test classpath.
    systemProperty("minikv.test.classpath", sourceSets["test"].runtimeClasspath.asPath)
    shouldRunAfter(tasks.named("test"))
}
