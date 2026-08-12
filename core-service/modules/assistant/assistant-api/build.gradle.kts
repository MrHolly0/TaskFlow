// assistant module public API: interfaces, DTOs

dependencies {
    implementation(project(":core-service:shared:common"))
    implementation("org.springframework:spring-context")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
