// 순수 코틀린 모듈 (안드로이드 의존성 없음)
// 프로젝트가 Version Catalog(libs.plugins...)를 쓰면 아래 plugins 줄을 그 방식에 맞게 바꾸세요.
plugins {
    // Reuse the Kotlin Gradle plugin already on the root build classpath.
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }
