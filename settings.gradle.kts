pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://repo.boox.com/repository/maven-public/") {
            content { includeGroup("com.onyx.android.sdk") }
        }
        maven("https://repo.boox.com/repository/proxy-public/") {
            content {
                includeGroup("com.jakewharton.hugo.fix")
                includeGroup("com.onyx.android.sdk")
                includeGroup("pub.devrel")
                includeGroup("com.tencent")
            }
        }
    }
}
rootProject.name = "BooxArt"
include(":app")
