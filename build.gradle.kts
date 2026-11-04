// AGP 9 bringt Kotlin selbst mit („built-in Kotlin“); das Plugin org.jetbrains.kotlin.android entfällt.
// Die Kotlin-Version wird über den Klassenpfad festgelegt, damit sie zum Compose-Compiler-Plugin passt.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
