pluginManagement {
    repositories {
        // Repositório local herdado do DoGCyberAgent: guarda os artefatos que o
        // Gradle não consegue baixar de forma confiável nesta máquina. A pasta não
        // vem versionada — se estiver vazia, os artefatos vêm do mavenCentral.
        maven {
            url = uri(rootDir.resolve("local-maven"))
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(rootDir.resolve("local-maven"))
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketHound"
include(":app")
