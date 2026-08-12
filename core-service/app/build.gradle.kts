plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.liquibase:liquibase-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
    runtimeOnly("org.postgresql:postgresql")

    implementation(project(":core-service:shared:common"))
    implementation(project(":core-service:shared:security"))
    implementation(project(":core-service:shared:persistence"))

    implementation(project(":core-service:modules:user:user-impl"))
    implementation(project(":core-service:modules:task:task-impl"))
    implementation(project(":core-service:modules:nlp-gateway:nlp-gateway-impl"))
    implementation(project(":core-service:modules:notify:notify-impl"))
    implementation(project(":core-service:modules:integration-telegram:integration-telegram-impl"))
    implementation(project(":core-service:modules:audit:audit-impl"))
    implementation(project(":core-service:modules:assistant:assistant-impl"))

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // implementation-зависимости impl-модулей на свои api не протекают транзитивно —
    // тестам app, обращающимся к доменным типам напрямую (LiveModelRegressionTest),
    // нужны собственные ссылки на нужные api-модули
    testImplementation(project(":core-service:modules:assistant:assistant-api"))
    testImplementation(project(":core-service:modules:task:task-api"))
    testImplementation(project(":core-service:modules:user:user-api"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live")
    }
}

// стоит денег и требует настоящего GROQ_API_KEY + работающего nlp-worker —
// не часть обычной сборки, запускается вручную: ./gradlew :core-service:app:liveTest
tasks.register<Test>("liveTest") {
    description = "Регрессия на живой модели (тег live)"
    group = "verification"
    useJUnitPlatform {
        includeTags("live")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}
