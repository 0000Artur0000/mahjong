plugins {
    base
}

tasks.register("checkAll") {
    group = "verification"
    dependsOn(":backend:check")
}

