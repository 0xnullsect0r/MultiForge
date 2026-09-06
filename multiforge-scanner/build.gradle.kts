plugins {
    id("multiforge.base")
    application
}

description = "MultiForge mod-safety scanner — static ASM-based bytecode analysis for known unsafe patterns."

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass.set("net.multiforge.scanner.Main")
}
