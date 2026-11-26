pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

include(":aa-display", ":lib-stub")//, ":lib-aasdk"

// Vendored AARE AAP protocol stack (prototux/AARE `aap` module, GPL-3.0-or-later).
// Local-only, never published. See vendor/aare-aap/NOTICE.md.
include(":aare-aap")
project(":aare-aap").projectDir = file("vendor/aare-aap")
// protobuf 生成件单独成模块，保证 Java 类先于 Kotlin 编译。
include(":aare-proto")
project(":aare-proto").projectDir = file("vendor/aare-aap/proto")

rootProject.name = "AADisplay"
