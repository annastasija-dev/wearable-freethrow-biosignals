package lt.vilniustech.basketball.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.concurrent.futures.await
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.io.File
import kotlinx.coroutines.tasks.await

/** Sends FT Watch APK to the Galaxy Watch already linked in Galaxy Wearable. */
object WearCompanionInstaller {
    private const val TAG = "WearCompanionInstall"

    data class Result(val ok: Boolean, val message: String)

    suspend fun sendToConnectedWatch(context: Context, apk: File): Result {
        PhoneWifiRelay.setWatchApkForInstall(apk)
        PhoneWifiRelay.ensureStarted(context)

        val localId = runCatching {
            com.google.android.gms.wearable.Wearable.getNodeClient(context).localNode.await().id
        }.getOrNull()
        val nodes = WearNodes.reachableWatchNodes(context)
            .filter { it.id != localId }
        Log.i(TAG, "wear nodes=${nodes.map { "${it.displayName}/${it.id}/nearby=${it.isNearby}" }} local=$localId diag=${WearNodes.lastDiagnostic}")
        if (nodes.isEmpty()) {
            return Result(
                false,
                "Watch not visible in Galaxy Wearable.\nOpen Galaxy Wearable, wait for Connected, then try again.",
            )
        }

        val watch = nodes.firstOrNull { it.isNearby } ?: nodes.first()
        val urls = PhoneWifiRelay.allWatchInstallUrls()
        if (urls.isEmpty()) {
            return Result(false, "Phone has no network address for the APK.")
        }
        Log.i(TAG, "target=${watch.displayName} urls=$urls")

        val helper = RemoteActivityHelper(context)
        var opened = 0
        val errors = mutableListOf<String>()
        val apkUrls = urls.filter { it.endsWith(".apk") }.take(4)
        val pageUrls = urls.filter { it.endsWith("/install") }.take(2)
        for (url in apkUrls + pageUrls) {
            val intent = if (url.endsWith(".apk")) {
                remoteIntents(url).first()
            } else {
                remoteIntents(url).last()
            }
            val ok = runCatching {
                helper.startRemoteActivity(intent, watch.id).await()
                true
            }.getOrElse {
                errors += "${it.javaClass.simpleName}:${it.message}"
                Log.w(TAG, "remote $url failed: ${it.message}")
                false
            }
            if (ok) {
                opened++
                Log.i(TAG, "opened on watch ${watch.displayName}: $url")
                break
            }
        }

        return if (opened > 0) {
            Result(
                true,
                "Sending to ${watch.displayName}. On the watch, tap Install.",
            )
        } else {
            Result(
                false,
                "Watch found (${watch.displayName}), but the install screen did not open.\n${errors.take(3).joinToString("\n")}",
            )
        }
    }

    private fun remoteIntents(url: String): List<Intent> {
        val uri = Uri.parse(url)
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val apk = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return listOf(apk, view)
    }
}
