plugins {
    // Auto-provisions the JDK requested by the toolchain block when it is not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "amazeawn"

include("common", "api", "order-processor")
