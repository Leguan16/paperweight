plugins {
    `config-kotlin`
    kotlin("plugin.serialization") version "1.8.21"
}

dependencies {
    implementation(libs.httpclient)
    implementation(libs.bundles.kotson)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)
    implementation(libs.slf4j.jdk14) // slf4j impl for hypo
    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.jbsdiff)

    implementation(libs.serialize.core)
    implementation(libs.serialize.json)

    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }
}
