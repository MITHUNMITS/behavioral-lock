// Root build file. Pinned to the exact versions available in the local Gradle
// cache so builds resolve fast and offline-friendly on this machine.
// NOTE: AGP 9.4.1 has built-in Kotlin support — the separate
// `org.jetbrains.kotlin.android` plugin must NOT be applied (AGP owns it).
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}