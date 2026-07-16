package com.procamera.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.procamera.recorder.ui.MainActivity

/**
 * Foreground Service (`camera|microphone`) started for the duration of a recording so it
 * can survive screen-off (§4.6). On Android 9+, a background app cannot access the camera
 * at all without one — starting this Service with `FOREGROUND_SERVICE_TYPE_CAMERA` /
 * `_MICROPHONE` is the mechanism that legally permits the recording pipeline to keep
 * streaming while the Activity is not in the foreground.
 *
 * **スコープ(重要)**: このServiceはパイプラインの所有権を持たない
 * ——`RecordingPipeline`は引き続き`CameraControlViewModel`が所有する。この
 * Serviceの役割は(1)フォアグラウンド通知とカメラ/マイクのバックグラウンド
 * アクセス許可、(2)`PARTIAL_WAKE_LOCK`によるCPU起床維持、の2点のみ。
 *
 * これでカバーされるのは「画面ロック/バックグラウンド化してもプロセス自体は
 * 生き続ける」場合の録画継続(`RecordingPipeline.detachPreviewSurface`が
 * プレビューを外してエンコーダ単体のセッションに切り替える、という組み合わせで
 * 実現)。カバーされないのは、Activity/ViewModelのプロセスごとの破棄
 * (タスクスワイプでの終了、メモリ不足によるプロセスkill)——これには
 * パイプラインの所有権自体をServiceへ移す、より大きなリファクタリングが必要で
 * 未着手。「フォアグラウンドサービスを実装した」ことは「無人録画が完全に
 * 安全になった」ことを意味しない。
 *
 * **実機未検証**: このAVDのカメラHALがVideoEncoder InputSurfaceへの
 * ストリーミング自体を拒否するため(docs/ARCHITECTURE.md参照)、録画そのものが
 * エミュレータ上で開始できず、このServiceの起動タイミング・通知・WakeLockの
 * 取得/解放は一連の流れとして未検証。個々のAndroid APIとしては標準的な用法。
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            // API 29: foregroundServiceType is declared in the manifest instead; the
            // no-type overload picks that up (FOREGROUND_SERVICE_TYPE_CAMERA/MICROPHONE
            // as explicit startForeground() arguments require API 30+).
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        // NOT_STICKY: this Service is purely slaved to the ViewModel-owned
        // RecordingPipeline and is started explicitly on each recording. If the process
        // is killed and the OS restarts this Service on its own (START_STICKY's whole
        // point), there is no pipeline left to attach to — that would resurrect a
        // "録画中…" notification + wake lock for a recording that no longer exists. Don't
        // resurrect a service with nothing to serve.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProCamera:RecordingWakeLock").apply {
            setReferenceCounted(false)
            // Safety ceiling in case stopService()/onDestroy() is never reached (e.g. the
            // process is killed and restarted via START_STICKY with no recording to
            // resume) — avoids an indefinite wake lock leak. Recording sessions this long
            // are not a realistic target for this app (school-festival single-set use).
            acquire(MAX_WAKE_LOCK_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ProCamera")
            .setContentText("録画中…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "録画中",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val MAX_WAKE_LOCK_DURATION_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
