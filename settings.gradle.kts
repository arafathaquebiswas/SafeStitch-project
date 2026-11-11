pluginManagement {
    repositories {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "SafeStitch"
include(":app")

runCatching {
    val previewScript = file("scripts/preview-server.mjs")
    val warmupFile = File("/var/www/assets/warmup.html")
    val nodeBin = File("/usr/local/bin/node")
    if (previewScript.isFile && nodeBin.canExecute()) {
        val listening = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 3000), 150)
                true
            }
        }.getOrDefault(false)
        val stale = !warmupFile.isFile || previewScript.lastModified() > warmupFile.lastModified()
        if (!listening || stale) {
            ProcessBuilder(nodeBin.absolutePath, previewScript.absolutePath)
                .directory(rootDir)
                .redirectOutput(File("/tmp/preview-server.log"))
                .redirectErrorStream(true)
                .start()
        }
    }
}

