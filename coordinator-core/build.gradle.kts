// coordinator-core — Pure Java library (NO Spring).
//
// 의도적으로 Spring 의존성 0. 어떤 Java framework (Spring, Quarkus, Micronaut,
// plain Tomcat) 에든 임베드 가능한 framework-independent 라이브러리.
//
// Micrometer-core 만 의존 (메트릭 인터페이스 표준). 실제 메트릭 backend
// (Prometheus, Datadog 등) 는 consumer 가 결정.

dependencies {
    // Nullability annotations — JSpecify is the modern Java standard
    // (replaces JSR-305 / FindBugs / Checker / JetBrains variants).
    // Annotations are RetentionPolicy.CLASS so they live in bytecode for
    // tooling (NullAway, IDE) without runtime overhead.
    implementation("org.jspecify:jspecify:1.0.0")

    // 메트릭 인터페이스 — Spring 무관, 표준 라이브러리
    implementation("io.micrometer:micrometer-core:1.12.0")

    // Logging facade
    implementation("org.slf4j:slf4j-api:2.0.9")

    // 테스트 — JUnit BOM 으로 jupiter + platform 버전 정합성 보장.
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9+ 는 platform-launcher 를 자동으로 추가하지 않음 (strict 화).
    // Gradle 8.x 에선 자동 감지였으나 9.x 부터 명시적 선언 필수.
    // 누락 시: "Failed to load JUnit Platform" 에러.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.9")
}

tasks.test {
    // 단위 테스트는 동시성 시뮬레이션 포함 — JVM 프로세스 별도 fork 로 격리.
    forkEvery = 100
    maxParallelForks = 1
}
