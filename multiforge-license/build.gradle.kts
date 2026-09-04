plugins {
    id("multiforge.base")
}

description = "Ed25519-signed license token verifier. No Minecraft dependency."

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
