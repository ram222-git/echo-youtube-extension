// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("uninstall") {
    dependsOn(":app:uninstall")
    group = "Install"
    description = "Uninstalls the debug build from connected devices."
}