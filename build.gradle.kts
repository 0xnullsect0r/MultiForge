plugins {
    id("multiforge.base") apply false
}

// Root project has no source. Meta tasks:
tasks.register("printModules") {
    group = "help"
    description = "Print the list of currently-included subprojects."
    doLast {
        subprojects.forEach { println(" - ${it.path}") }
    }
}
