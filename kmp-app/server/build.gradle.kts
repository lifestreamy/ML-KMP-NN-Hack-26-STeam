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
    implementation(projects.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)

    implementation(libs.ktor.server.call.logging)

    implementation(libs.ktor.serialization.json)

    // Client deps
//    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)


    implementation(libs.logback)
    implementation(libs.koin.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}