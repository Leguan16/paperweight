plugins {
    `config-kotlin`
}

dependencies {
    implementation(libs.httpclient)
    implementation(libs.bundles.kotson)
    implementation(libs.coroutines)
    implementation(libs.jgit)
    implementation(libs.diffutils)

    // ASM for inspection
    implementation(libs.bundles.asm)

    implementation(libs.bundles.hypo)
    implementation(libs.slf4j.jdk14) // slf4j impl for hypo
    implementation(libs.bundles.cadix)

    implementation(libs.lorenzTiny)

    implementation(libs.feather.core)
    implementation(libs.feather.gson)

    implementation(libs.jbsdiff)

    implementation(libs.codebook)

    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }
}
