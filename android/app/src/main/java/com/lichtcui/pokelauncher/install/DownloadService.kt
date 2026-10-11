package com.lichtcui.pokelauncher.install

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lichtcui.pokelauncher.MainActivity
import com.lichtcui.pokelauncher.R
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 承载资源下载的前台服务（`dataSync` 类型 + 进度通知 + 暂停/取消 action）。
 *
 * 对应鸿蒙版 `request.agent` 的**系统后台下载任务**：App 被杀后下载不会继续
 * （Android 没有等价物），但分片与状态文件都留着，回到 App 点「继续」即可续传。
 *
 * ⚠️ targetSdk 35 的三条硬约束（违反就是运行时崩溃/被系统拒绝）：
 * 1. 必须声明 `android:foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC` 权限；
 * 2. API 33+ 要先拿到 `POST_NOTIFICATIONS`（打靶页负责申请；没有时 FGS 通知仍会显示，
 *    但「下载完成/失败」的普通通知不会显示）；
 * 3. API 34+ 不允许后台自行启动前台服务：**所有启动都必须由用户操作触发**（点按钮）。
 *
 * Android 15 起 `dataSync` 前台服务在 24 小时内累计只能跑 6 小时，到点系统会调
 * [onTimeout]：这里保存续传状态并停止服务，而不是被系统判成 ANR。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: GameRepository
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 收尾（已 stopSelf / 被系统超时停掉）后不再回写通知，否则会留下一张划不掉的常驻通知 */
    @Volatile
    private var notificationLive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = GameRepository(GameRepository.rootDirOf(this))
        createChannel()
        Log.i(Const.TAG, "DownloadService created, root=${repo.rootDir.absolutePath}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5s 内进入前台；顺带覆盖「服务刚被系统回收，又被通知 action 拉起」的情况
        enterForeground(DownloadBus.status.value)

        when (intent?.action) {
            ACTION_PAUSE -> {
                Log.i(Const.TAG, "下载：收到暂停")
                repo.pause()
            }

            ACTION_CANCEL -> {
                Log.i(Const.TAG, "下载：收到取消")
                repo.cancel()
            }

            else -> begin(intent?.getBooleanExtra(EXTRA_FRESH, false) ?: false)
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+：`dataSync` 前台服务超时（6 小时 / 24 小时）。
     * 系统只给几秒钟收尾时间，必须在这里保存进度并停止，否则会被判 ANR。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(Const.TAG, "dataSync 前台服务超时（系统上限），保存进度后停止")
        repo.pause()
        DownloadBus.update {
            it.copy(phase = DownloadPhase.PAUSED, message = "系统已停止后台下载，回到 App 可继续")
        }
        notificationLive = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        notificationLive = false
        // 进程被回收时把进度落盘：分片留着，下次进 App 点「继续」即可
        if (job?.isActive == true) {
            Log.w(Const.TAG, "DownloadService 被销毁但下载未完，保存续传状态")
            repo.pause()
        }
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 下载流程

    private fun begin(fresh: Boolean) {
        if (job?.isActive == true) {
            Log.w(Const.TAG, "已有下载任务在进行，忽略本次启动")
            return
        }
        acquireWakeLock()
        job = scope.launch {
            val info = try {
                withContext(Dispatchers.IO) { repo.resolveRelease() }
            } catch (e: Exception) {
                Log.e(Const.TAG, "查询资源包信息失败: ${e.message}")
                finish(DownloadOutcome.Failed("查询资源包信息失败：${e.message ?: "网络不可用"}"))
                return@launch
            }

            publish(
                DownloadStatus(
                    phase = DownloadPhase.PROBING,
                    total = info.assetSize,
                    tag = info.tag,
                    message = "正在测速，选择最快的下载源…"
                )
            )

            val outcome = try {
                repo.run(info, fresh) { status -> publish(status) }
            } catch (e: Exception) {
                Log.e(Const.TAG, "下载链路异常: ${e.message}", e)
                DownloadOutcome.Failed("下载失败：${e.message ?: e.javaClass.simpleName}")
            }
            finish(outcome)
        }
    }

    /** 终态由服务统一发布，仓库只回报结果，避免两处各写一份阶段文案 */
    private fun finish(outcome: DownloadOutcome) {
        val current = DownloadBus.status.value
        when (outcome) {
            DownloadOutcome.Installed -> {
                DownloadBus.publish(
                    current.copy(
                        phase = DownloadPhase.DONE,
                        received = if (current.total > 0) current.total else current.received,
                        message = "下载并安装完成",
                        error = ""
                    )
                )
                notifyFinal("PokeRogue 资源已就绪", "下载并安装完成，打开 App 即可离线游玩")
                stopForegroundAndSelf()
            }

            DownloadOutcome.Paused -> {
                // 暂停时保留通知（带「继续」action），否则用户只能回 App 才能续传
                publish(
                    current.copy(
                        phase = DownloadPhase.PAUSED,
                        received = repo.partialBytes(),
                        message = "已暂停 · 已完成 ${Format.bytes(repo.partialBytes())}"
                    )
                )
                releaseWakeLock()
            }

            DownloadOutcome.Cancelled -> {
                DownloadBus.publish(DownloadStatus(phase = DownloadPhase.CANCELLED, message = "已取消"))
                stopForegroundAndSelf()
            }

            is DownloadOutcome.Failed -> {
                // 失败晚于下载开始时保留进度数字；若是查询/预检阶段的早期失败，别把上一轮的
                // DONE/PAUSED 快照（tag、已下载字节）串进来
                val base = if (current.isActive || current.phase == DownloadPhase.PAUSED) {
                    current
                } else {
                    DownloadStatus()
                }
                DownloadBus.publish(
                    base.copy(
                        phase = DownloadPhase.FAILED,
                        received = repo.partialBytes(),
                        message = outcome.message,
                        error = outcome.message
                    )
                )
                notifyFinal("资源下载失败", outcome.message)
                stopForegroundAndSelf()
            }
        }
    }

    private fun stopForegroundAndSelf() {
        notificationLive = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ 通知

    /** UI 与通知的唯一数据源都是 [DownloadBus]，这里只负责「状态变了就刷新通知」 */
    private fun publish(status: DownloadStatus) {
        DownloadBus.publish(status)
        if (notificationLive) updateNotification(status)
    }

    private fun enterForeground(status: DownloadStatus) {
        // ServiceCompat 在 API 26~28 上会忽略 type 参数（那时还没有 foregroundServiceType 概念）
        ServiceCompat.startForeground(
            this,
            Const.DOWNLOAD_NOTIFICATION_ID,
            buildNotification(status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(status: DownloadStatus) {
        postNotification(Const.DOWNLOAD_NOTIFICATION_ID, buildNotification(status))
    }

    /**
     * 所有普通通知的统一出口。
     * API 33+ 未授权 `POST_NOTIFICATIONS` 时 `notify()` 会被系统静默丢弃（前台服务通知不受影响），
     * 所以先显式判权限：既满足 lint 的 `MissingPermission` 要求，也避免无意义的调用。
     */
    private fun postNotification(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(Const.TAG, "通知权限不足，跳过本次通知：${e.message}")
        }
    }

    private fun buildNotification(status: DownloadStatus): Notification {
        val title = when (status.phase) {
            DownloadPhase.PROBING -> "正在寻找最快的下载源"
            DownloadPhase.DOWNLOADING -> "正在下载 PokeRogue 资源"
            DownloadPhase.PAUSED -> "下载已暂停"
            DownloadPhase.EXTRACTING -> "正在解压 PokeRogue 资源"
            else -> "PokeRogue 资源下载"
        }
        val builder = NotificationCompat.Builder(this, Const.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(DownloadText.describe(status))
            .setStyle(NotificationCompat.BigTextStyle().bigText(DownloadText.describe(status)))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(status.phase == DownloadPhase.DOWNLOADING || status.phase == DownloadPhase.PAUSED)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (status.total > 0 && status.phase != DownloadPhase.PROBING) {
            builder.setProgress(100, status.percent, false)
        } else {
            // 测速 / 解压阶段拿不到百分比：不确定进度条
            builder.setProgress(0, 0, true)
        }

        if (status.canPause) {
            builder.addAction(0, "暂停", serviceIntent(ACTION_PAUSE, null))
        } else if (status.canResume) {
            builder.addAction(0, "继续", serviceIntent(ACTION_START, false))
        }
        if (status.canCancel) {
            builder.addAction(0, "取消", serviceIntent(ACTION_CANCEL, null))
        }
        return builder.build()
    }

    /** 下载结束（成功/失败）的普通通知：点一下回到 App */
    private fun notifyFinal(title: String, text: String) {
        val builder = NotificationCompat.Builder(this, Const.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        // API 33+ 未授权 POST_NOTIFICATIONS 时这条会被系统丢掉（前台服务的通知不受影响）
        postNotification(Const.DOWNLOAD_NOTIFICATION_ID + 1, builder.build())
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 0, intent, PENDING_FLAGS)
    }

    private fun serviceIntent(action: String, fresh: Boolean?): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(action)
        if (fresh != null) intent.putExtra(EXTRA_FRESH, fresh)
        return PendingIntent.getForegroundService(this, action.hashCode(), intent, PENDING_FLAGS)
    }

    private fun createChannel() {
        // minSdk 26 → NotificationChannel 一定可用，不必再判 SDK_INT
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            Const.DOWNLOAD_CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------ 其它

    /**
     * 507MB 下到一半屏幕熄灭、CPU 休眠会让 socket 卡住，因此持有 partial wake lock。
     * 带上限（超时自动释放）而不是无限持有：万一逻辑有漏，也不会变成「后台耗电」投诉。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${Const.TAG}:download").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val ACTION_START = "com.lichtcui.pokelauncher.download.START"
        private const val ACTION_PAUSE = "com.lichtcui.pokelauncher.download.PAUSE"
        private const val ACTION_CANCEL = "com.lichtcui.pokelauncher.download.CANCEL"
        private const val EXTRA_FRESH = "fresh"

        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60 * 1000

        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** 开始/继续下载。`fresh = true` 表示丢弃已有分片从头下（换源重试） */
        fun start(context: Context, fresh: Boolean) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FRESH, fresh)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        /** 这些动作都来自界面按钮 / 通知 action，都在「用户操作触发前台服务」的豁免范围内 */
        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(action)
            )
        }
    }
}
