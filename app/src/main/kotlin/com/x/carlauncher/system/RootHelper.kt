package com.x.carlauncher.system

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Result of a root shell command execution.
 */
data class CommandResult(
    val exitCode: Int,
    val output: String,
    val error: String,
    val success: Boolean = exitCode == 0
)

/**
 * Privileged root helper for XCarOS on rooted Automotive Head Units.
 * Executes commands non-blockingly with timeouts and provides automotive-specific
 * system optimizations (CPU governor, battery preservation, permissions, ADB over Wi-Fi).
 */
object RootHelper {

    private const val TAG = "XCarOS_Root"

    private val SU_PATHS = arrayOf(
        "/product/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su"
    )

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * Checks if the device has a working root (su) binary.
     * Caches the result after the first successful probe.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        cachedRootAvailable?.let { return@withContext it }

        // Fast path: check file existence
        val binaryExists = SU_PATHS.any { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                false
            }
        }

        if (!binaryExists) {
            cachedRootAvailable = false
            return@withContext false
        }

        // Verification path: test execution of 'id'
        val result = execRootInternal("id", timeoutMs = 2000L)
        val isRoot = result.success && (result.output.contains("uid=0") || result.output.contains("root"))
        cachedRootAvailable = isRoot
        if (isRoot) {
            Log.i(TAG, "Root access confirmed: ${result.output.trim()}")
        }
        isRoot
    }

    /**
     * Executes a command in a root shell (`su`) safely on Dispatchers.IO with a timeout.
     */
    suspend fun execRoot(command: String, timeoutMs: Long = 5000L): CommandResult =
        withContext(Dispatchers.IO) {
            execRootInternal(command, timeoutMs)
        }

    fun execRootInternal(command: String, timeoutMs: Long = 5000L): CommandResult {
        return try {
            val suBin = if (cachedRootAvailable == false) null else SU_PATHS.firstOrNull { File(it).exists() }
            if (suBin == null) return CommandResult(-1, "", "No root binary", false)
            val process = Runtime.getRuntime().exec(suBin)
            val os = DataOutputStream(process.outputStream)
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            // Write command and exit
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            // Read output streams in parallel
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                Log.w(TAG, "Command timed out after ${timeoutMs}ms: $command")
                return CommandResult(-1, "", "Timeout after ${timeoutMs}ms", false)
            }

            var line: String?
            while (outReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }
            while (errReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            val exitCode = process.exitValue()
            CommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim(), exitCode == 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec root command: $command", e)
            CommandResult(-1, "", e.message ?: "Exception", false)
        }
    }

    /**
     * Executes all privileged initial configurations in a SINGLE batched root call
     * during the first launch only, completely eliminating repeated Magisk/SuperSU toast notifications.
     */
    suspend fun performOneTimeSetup(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        val pkg = context.packageName
        val notifComponent = "$pkg/com.x.carlauncher.media.PowerampNotificationListener"

        val combinedScript = listOf(
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION 2>/dev/null",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION 2>/dev/null",
            "pm grant $pkg android.permission.READ_EXTERNAL_STORAGE 2>/dev/null",
            "appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null",
            "dumpsys deviceidle whitelist +$pkg 2>/dev/null",
            "settings put secure enabled_notification_listeners \"$notifComponent\" 2>/dev/null",
            "settings put global stay_on_while_plugged_in 0 2>/dev/null",
            "setprop persist.adb.tcp.port 5555 2>/dev/null",
            "setprop service.adb.tcp.port 5555 2>/dev/null"
        ).joinToString(" ; ")

        val res = execRootInternal(combinedScript, timeoutMs = 6000L)
        Log.i(TAG, "One-time batched setup executed (success=${res.success})")
        res.success
    }

    /**
     * Locks XCarOS as the exclusive, permanent Home Launcher.
     * Sets home activity and disables legacy vendor launchers on user 0 so HOME button
     * always routes strictly to XCarOS.
     */
    suspend fun lockAsDefaultHomeLauncher(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        val pkg = context.packageName
        val activity = "$pkg/com.x.carlauncher.MainActivity"
        Log.i(TAG, "Setting $activity as default Home activity")

        val vendorLaunchers = listOf(
            "com.android.launcher2", "com.android.launcher3", "com.android.launcher4",
            "com.android.launcher5", "com.android.launcher8", "com.android.launcher11",
            "com.android.launcher20", "com.android.launcher24", "com.android.launcher27",
            "com.android.launcher30", "com.android.launcher43", "com.android.launcher44",
            "com.android.launcher47", "com.android.launcher51"
        )

        val disableCommands = vendorLaunchers.joinToString(" ; ") { l ->
            "pm disable-user --user 0 $l 2>/dev/null ; pm clear-preferred-activities $l 2>/dev/null"
        }

        val cmd = "$disableCommands ; cmd package set-home-activity $activity"
        val res = execRootInternal(cmd, 6000L)
        Log.i(TAG, "Lock home activity result: ${res.output.trim()} (success=${res.success})")
        res.success
    }

    /**
     * Enables permanent ADB over Wi-Fi on port 5555.
     */
    suspend fun enableAdbOverWifi(port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        Log.i(TAG, "Enabling ADB TCP/IP on port $port via root")
        val cmd = "setprop persist.adb.tcp.port $port && setprop service.adb.tcp.port $port && stop adbd && start adbd"
        val res = execRootInternal(cmd, 4000L)
        res.success
    }

    /**
     * Tunes CPU frequency governor for buttery-smooth 60 FPS UI performance,
     * trims background memory bloat, and tunes VM cache parameters.
     */
    suspend fun optimizeCpuPerformance(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        val cmd = """
            for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
                echo performance > "${'$'}g" 2>/dev/null || echo schedutil > "${'$'}g" 2>/dev/null
            done
            echo 60 > /proc/sys/vm/swappiness 2>/dev/null
            echo 100 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null
            pm disable android.rockchip.update.service 2>/dev/null
            pm disable com.google.android.googlequicksearchbox 2>/dev/null
            am force-stop com.google.android.googlequicksearchbox 2>/dev/null
            am force-stop cm.aptoide.pt 2>/dev/null
            am force-stop android.rockchip.update.service 2>/dev/null
            cmd appops set cm.aptoide.pt RUN_IN_BACKGROUND ignore 2>/dev/null
            cmd appops set com.google.android.googlequicksearchbox RUN_IN_BACKGROUND ignore 2>/dev/null
        """.trimIndent()
        val res = execRootInternal(cmd, 4000L)
        Log.i(TAG, "CPU & System Memory tuned for maximum fluency")
        res.success
    }

    /**
     * Enters low-power standby mode when ignition is turned off (ACC OFF).
     */
    suspend fun standbyPowerDown(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        Log.i(TAG, "ACC OFF: Entering root low-power standby")
        val cmd = "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo powersave > \"\$g\" 2>/dev/null || echo conservative > \"\$g\" 2>/dev/null; done"
        val res = execRootInternal(cmd, 3000L)
        res.success
    }

    /**
     * Restores high-performance mode when ignition is turned on (ACC ON).
     */
    suspend fun restorePowerUp(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        Log.i(TAG, "ACC ON: Restoring root high-performance")
        optimizeCpuPerformance()
    }

    /**
     * Reboots the automotive head unit cleanly.
     */
    suspend fun reboot(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        Log.i(TAG, "Rebooting head unit via root")
        val res = execRootInternal("svc power reboot || reboot", 5000L)
        res.success
    }
}

