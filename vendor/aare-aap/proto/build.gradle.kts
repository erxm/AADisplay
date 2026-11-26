// AAP protobuf 定义单独成一个 java-library 模块：
// Kotlin（:aare-aap）只有在 Java 源码先编译成 class 之后才能解析 aap_protobuf.*，
// 所以生成 / 编译拆到独立模块，主模块通过 classpath 消费。
plugins {
    id("java-library")
    id("com.google.protobuf") version "0.9.5"
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.5")
}

// src/main/proto is for protoc only — do not ship .proto sources in the JAR.
tasks.withType<Jar>().configureEach {
    exclude("**/*.proto")
}
