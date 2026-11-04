pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos SDK는 Pleos 저장소에서만 받고, 그 저장소에서는 Pleos SDK만 받는다(의존성 혼동 공격 방지).
        exclusiveContent {
            forRepository { maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") } }
            filter { includeGroup("ai.pleos.playground") }
        }
    }
}

rootProject.name = "Taraang"
include(":app")
