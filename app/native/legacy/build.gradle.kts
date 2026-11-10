plugins { id("com.android.application") }

android {
    namespace = "com.studyontv.legacy"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.studyontv.legacy"
        minSdk = 14
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

val syncPractice by tasks.registering(Sync::class) {
    val web = rootProject.projectDir.parentFile.resolve("web")
    from(web) { include("practice.html", "practice.appcache", "assets/css/practice.css", "assets/js/practice.js") }
    into(layout.buildDirectory.dir("generated/practice-assets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/practice-assets"))
tasks.named("preBuild") { dependsOn(syncPractice) }
