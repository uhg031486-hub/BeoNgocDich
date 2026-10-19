package vn.huy.truyendich

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AppUpdateManager {

    private const val UPDATE_CONFIG_URL =
        "https://raw.githubusercontent.com/uhg031486-hub/BeoNgocDich/main/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val title: String,
        val changelog: String,
        val apkUrl: String
    )

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    fun checkForUpdate(activity: Activity, showToastIfLatest: Boolean = false) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL(UPDATE_CONFIG_URL)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (connection.responseCode in 200..299) {
                    val raw = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val json = JSONObject(raw)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val title = json.optString("title", "Phiên bản mới")
                    val changelog = json.optString("changelog", "Cải tiến hiệu năng và cập nhật tính năng mới.")
                    val apkUrl = json.optString("apkUrl", "")

                    val currentVersionCode = getCurrentVersionCode(activity)
                    val currentVersionName = getCurrentVersionName(activity)

                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            if (remoteVersionCode.toLong() > currentVersionCode && apkUrl.isNotEmpty()) {
                                showUpdateDialog(
                                    activity,
                                    UpdateInfo(
                                        versionCode = remoteVersionCode,
                                        versionName = remoteVersionName,
                                        title = title,
                                        changelog = changelog,
                                        apkUrl = apkUrl
                                    )
                                )
                            } else if (showToastIfLatest) {
                                Toast.makeText(
                                    activity,
                                    "Ứng dụng đang ở bản mới nhất (v$currentVersionName)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else if (showToastIfLatest) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Không thể kết nối máy chủ kiểm tra cập nhật.", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                if (showToastIfLatest) {
                    activity.runOnUiThread {
                        val currentVersionName = getCurrentVersionName(activity)
                        Toast.makeText(activity, "Đang ở bản mới nhất (v$currentVersionName)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("🚀 ${info.title} (v${info.versionName})")
            .setMessage("Nội dung cập nhật:\n\n${info.changelog}\n\nBạn có muốn cập nhật ngay bây giờ?")
            .setCancelable(false)
            .setPositiveButton("CẬP NHẬT NGAY") { _, _ ->
                downloadAndInstallApk(activity, info.apkUrl, "BeoNgocDich_v${info.versionName}.apk")
            }
            .setNegativeButton("ĐỂ SAU", null)
            .show()
    }

    fun downloadAndInstallApk(context: Context, url: String, fileName: String) {
        Toast.makeText(context, "Đang tải bản cập nhật mới trong nền...", Toast.LENGTH_LONG).show()

        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val destinationUri = Uri.fromFile(
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            )

            val targetFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Cập nhật Béo Ngọc Dịch")
                .setDescription("Đang tải $fileName...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(destinationUri)
                .setMimeType("application/vnd.android.package-archive")

            val downloadId = downloadManager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == downloadId) {
                        try {
                            ctx.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        installApk(ctx, targetFile)
                    }
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(onComplete, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(onComplete, filter)
            }
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Lỗi tải cập nhật: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "Không tìm thấy tệp cài đặt!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Không thể mở trình cài đặt: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
