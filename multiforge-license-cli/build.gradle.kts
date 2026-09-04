plugins {
    id("multiforge.base")
    application
}

description = "CLI to generate Ed25519 keypairs and sign MultiForge license tokens."

dependencies {
    implementation(project(":multiforge-license"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass.set("net.multiforge.license.cli.Main")
}
