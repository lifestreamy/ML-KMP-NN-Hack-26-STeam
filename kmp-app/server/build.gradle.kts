plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.superteam.app"
version = "1.0.0"
application {
    mainClass = "com.superteam.app.ApplicationKt"
}

dependencies {
    implementation(projects.core) // Зависимость на общие модели

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.serialization)

    implementation(libs.logback)
    implementation(libs.koin.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}