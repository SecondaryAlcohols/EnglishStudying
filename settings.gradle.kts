pluginManagement {
    repositories {
        // 国内镜像优先。实测：maven.aliyun.com 0.4s、dl.google.com 0.6s、
        // repo1.maven.org 3.7s —— 慢的是 Maven Central，而首次构建要从中拉几百 MB。
        // 镜像放在前面，没命中时会自动回落到下面的官方源。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")

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
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")

        google()
        mavenCentral()
    }
}

rootProject.name = "EnglishStudying"
include(":app")
