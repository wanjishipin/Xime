fun executeCommand(command: String): String {
    return try {
        val parts = command.split(" ")
        val process = ProcessBuilder(parts)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        ""
    }
}

tasks.register("clearPlugins", DefaultTask::class) {
    group = "plugin-dev"
    description = "Clear all plugin data from device (requires connected device with adb)"

    doLast {
        val packageName = "com.kingzcheung.xime"
        val pluginsDir = "/data/data/$packageName/files/plugins"

        println("=== Clearing Xime plugin data ===")

        val devicesCheck = executeCommand("adb devices")
        if (!devicesCheck.contains("device")) {
            println("ERROR: No connected device detected")
        } else {
            println("Clearing plugin directory...")
            executeCommand("adb shell rm -rf $pluginsDir")

            println("Clearing plugin config...")
            executeCommand("adb shell rm -rf /data/data/$packageName/shared_prefs/plugin_*.xml")
            executeCommand("adb shell rm -rf /data/data/$packageName/shared_prefs/plugins.xml")

            println("=== Done ===")
            println("Please restart Xime app to reload plugins")
        }
    }
}

tasks.register("uninstallApp", DefaultTask::class) {
    group = "plugin-dev"
    description = "Completely uninstall Xime app (clear all data)"

    doLast {
        val packageName = "com.kingzcheung.xime"

        println("=== Completely uninstalling Xime app ===")

        val devicesCheck = executeCommand("adb devices")
        if (!devicesCheck.contains("device")) {
            println("ERROR: No connected device detected")
        } else {
            println("Uninstalling $packageName...")
            val result = executeCommand("adb uninstall $packageName")
            println(result)

            println("=== Done ===")
            println("All app data cleared. Reinstall to start fresh.")
        }
    }
}
