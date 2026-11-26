plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // 上游 AARE 用 Kotlin 1.9 编译；只把 2.0 不再接受的写法（inline lambda 内的
        // 非局部 break/continue）就地改掉了，不再降语言级别。
    }
}

dependencies {
    // protoc 生成的 Java 类（aap_protobuf.*）由 :aare-proto 编译，这里只消费其 classpath。
    api(project(":aare-proto"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// 不把 .proto 源码打进 JAR（proto 源码已移到 :aare-proto）。
tasks.withType<Jar>().configureEach {
    exclude("**/*.proto")
}
