plugins {
    base
}

tasks.named("check") {
    dependsOn(":backend:check")
}
