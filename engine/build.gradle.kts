description = "LSM-tree storage engine. No networking and no runtime dependencies."

dependencies {
    testImplementation("net.jqwik:jqwik:1.9.2")
}

tasks.withType<JavaCompile>().configureEach {
    // jqwik reads parameter names for reporting.
    options.compilerArgs.add("-parameters")
}
