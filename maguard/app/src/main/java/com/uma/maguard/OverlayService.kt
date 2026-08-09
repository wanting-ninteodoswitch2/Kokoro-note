package com.uma.maguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一時停止画面をオーバーレイとして表示する。
 *
 * ＜3つのモード＞
 * MODE_LAUNCH      … 開いた瞬間。「まだ、見たい？」
 * MODE_USAGE       … 連続使用が上限を超えた。「n分、経ちました」
 * MODE_DAILY_LIMIT … 1日の上限に到達。「見る」を出さない
 *
 * 実測値（今日の合計時間・起動回数）を画面に出すことで、
 * 「思ったより使っている」という事実を突きつける。
 * 記事にあった「気づけば3時間後」を、気づける形にするのが狙い。
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var timer: CountDownTimer? = null
    private var friction: FrictionController? = null

    // いまどのアプリのために表示しているか。
    // 別アプリの要求が来たときに差し替えるかどうかの判断に使う。
    private var currentTarget: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Android 8以降、バックグラウンドから起動されたサービスは
        // 5秒以内に startForeground を呼ばないと強制終了される。
        // アラーム経由でも確実に表示できるよう、必ず最初に呼ぶ。
        //
        // 型を渡す3引数版は API 29 で追加されたが、ここで渡している
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 自体が API 34 で追加された
        // 定数であり、マニフェストの specialUse も API 34 以降でしか
        // 有効にならない。そのため 34 未満では型なしの2引数版を使う。
        //
        // ＜ここを try-catch で囲む理由＞
        // startForeground はバックグラウンド起動制限（Android 12+）により
        // ForegroundServiceStartNotAllowedException を投げることがある。
        // 呼び出し元（MaAccessibilityService / UsageAlarmScheduler）側にも
        // try-catch はあるが、それは startForegroundService の呼び出し自体を
        // 囲んでいるだけで、OSが別タイミングで呼び出すこの onCreate の中の
        // 例外までは防げない。ここで捕まえずに落ちると ま。Guard プロセス
        // 全体がクラッシュし、繰り返し発生するとOSがアクセシビリティ
        // サービスそのものを強制停止させてしまう（設定画面で
        // 「動作していません」と表示される状態）。
        // 表示できないだけなら、諦めて自分を止める方が実害が小さい。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            instance = null
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (pkg == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // すでに何か表示している場合の扱い。
        //
        // 同じアプリなら二重に出す必要はないので無視してよい。
        // だが別のアプリなら話が違う。以前はここで一律に無視していたため、
        // 次のような抜け道があった：
        //
        //   1. Instagram（対象）を開く → オーバーレイAが出る
        //   2. 履歴から X（対象）に切り替える
        //   3. Xの検知は走るが overlayView != null で無視される
        //   4. 画面に残っているAで「見る」を押す
        //   → 猶予が付くのは Instagram。目の前の X は素通しで使える
        //
        // 別アプリの要求が来たら、必ず出し直す。
        if (overlayView != null) {
            if (pkg == currentTarget) return START_NOT_STICKY
            removeOverlayViewOnly()
        }

        currentTarget = pkg
        showOverlay(
            packageName = pkg,
            mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LAUNCH,
            sessionMinutes = intent.getIntExtra(EXTRA_SESSION_MINUTES, 0),
            todayMinutes = intent.getIntExtra(EXTRA_TODAY_MINUTES, 0),
            todayLaunches = intent.getIntExtra(EXTRA_TODAY_LAUNCHES, 0)
        )
        return START_NOT_STICKY
    }

    private fun showOverlay(
        packageName: String,
        mode: String,
        sessionMinutes: Int,
        todayMinutes: Int,
        todayLaunches: Int
    ) {
        // 権限はインストール時ではなくユーザーが設定画面で与えるもので、
        // 後から取り消すこともできる。取り消された状態で addView を呼ぶと
        // WindowManager.BadTokenException でクラッシュするため、
        // 表示の直前に必ず確認する。
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val prefs = Prefs(this)
        val config = prefs.getEffectiveConfig(packageName) ?: Prefs.AppConfig()
        val activeRule = prefs.findActiveRule()
        val hardBlock = activeRule?.blocked == true || mode == MODE_DAILY_LIMIT

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_pause, null)
        overlayView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 理由入力・書き写しではキーボードを使うため、フォーカスを受け取れる
        // ウィンドウにする必要がある。FLAG_NOT_FOCUSABLE は付けない。
        // SOFT_INPUT_ADJUST_RESIZE でキーボード表示時に画面を縮める。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // canDrawOverlays を通っていても、メーカー独自ROMの制限などで
        // addView が失敗することがある。ここで落ちると通常の
        // アプリ利用まで巻き添えにするため、失敗しても静かに諦める。
        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            overlayView = null
            stopSelf()
            return
        }

        // このウィンドウはフォーカスを受け取るため、戻るキーを自前で処理しないと
        // 何も起きず「閉じられない」状態になる。戻る＝やめておく、として扱う。
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                MaAccessibilityService.clearGrace(this, packageName)
                UsageAlarmScheduler.cancel(this, packageName)
                record(resisted = true)
                removeOverlay()
                goHome()
                true
            } else {
                false
            }
        }

        val headline = view.findViewById<TextView>(R.id.headlineText)
        val ruleText = view.findViewById<TextView>(R.id.ruleText)
        val factText = view.findViewById<TextView>(R.id.factText)
        val countdownText = view.findViewById<TextView>(R.id.countdownText)
        val breathingView = view.findViewById<BreathingView>(R.id.breathingView)
        val breathContainer = view.findViewById<View>(R.id.breathContainer)
        val questionGroup = view.findViewById<View>(R.id.questionGroup)
        val questionText = view.findViewById<TextView>(R.id.questionText)
        val openButton = view.findViewById<Button>(R.id.openButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)

        // モードごとの文言
        when (mode) {
            MODE_USAGE -> {
                // 90秒以内の短い中断は同じセッションとして数えているため、
                // 「連続」ではなく「このセッションで」という言い方にする
                headline.text = "${sessionMinutes}分、見ています。"
                questionText.text = "まだ、続ける？"
                openButton.text = "もう少しだけ"
                cancelButton.text = "ここで終わる"
            }
            MODE_DAILY_LIMIT -> {
                headline.text = "今日の上限に達しました。"
                questionText.text = "今日は、ここまで。"
                cancelButton.text = "閉じる"
            }
            else -> {
                headline.text = "ひと呼吸。"
                questionText.text = "まだ、見たい？"
                openButton.text = "見る"
                cancelButton.text = "やめておく"
            }
        }

        // 実測値の提示。これが一番効く情報なので、常に見せる
        val facts = mutableListOf<String>()
        if (todayMinutes > 0) facts.add("今日 ${formatDuration(todayMinutes)}")
        if (todayLaunches > 0) facts.add("${todayLaunches}回目")
        if (facts.isNotEmpty()) {
            factText.visibility = View.VISIBLE
            factText.text = facts.joinToString("・")
        } else {
            factText.visibility = View.GONE
        }

        if (activeRule != null) {
            ruleText.visibility = View.VISIBLE
            ruleText.text = "${activeRule.label}（${activeRule.rangeLabelWithNote()}）"
        } else {
            ruleText.visibility = View.GONE
        }

        // ＜「やめておく」は最初から見せる＞
        //
        // PNAS の研究（Grüning et al., 2023）は、この種の介入を
        //   (1) 離脱の選択肢を出す
        //   (2) 時間遅延による摩擦
        //   (3) 熟慮を促すメッセージ
        // の3要素に分解して効果を比較し、最も効果が大きいのは
        // (1) の「離脱の選択肢」だと結論づけている。
        //
        // 以前の実装はカウントダウンが終わるまで選択肢を隠していたため、
        // 一番効くものを待ち時間の後ろに置いてしまっていた。
        // やめたい人ほど待たされる、という逆の設計になっていた。
        //
        // 現在は「やめておく」を即座に押せるようにし、
        // 待ち時間は「見る」側にだけ課している。
        // 摩擦は開くことに対してかけるものであって、やめることに
        // かけるものではない。
        questionGroup.visibility = View.VISIBLE
        // GONE ではなく INVISIBLE にして場所だけ先に確保しておく。
        // GONE だと「見る」が現れた瞬間にレイアウト全体が動き、
        // 指を置いていた位置に突然ボタンが来て誤タップを招く。
        // 開くつもりがなかった人を開かせてしまうのは最悪の失敗なので、
        // 表示位置は最初から固定しておく。
        openButton.visibility = View.INVISIBLE
        openButton.isEnabled = false
        view.findViewById<View>(R.id.frictionGroup).visibility = View.GONE
        countdownText.text = config.pauseSeconds.toString()
        breathingView.start()

        // 500ms 余分に持たせているのは、CountDownTimer の刻みが
        // ぴったり1秒ではないため。素直に秒数×1000 を渡すと、
        // 最初の数字が一瞬で消えたり、最後の1秒が極端に短くなったりして、
        // 「間を取る」ための画面としては落ち着かない見え方になる。
        timer = object : CountDownTimer(config.pauseSeconds * 1000L + 500L, 1000L) {
            override fun onTick(ms: Long) {
                countdownText.text = ((ms / 1000) + 1).toString()
            }

            override fun onFinish() {
                countdownText.text = ""
                // 待ち時間が明けたら呼吸の円は役目を終える。
                // 残しておくと、選択を迫られている場面で視線が散る。
                breathingView.stop()
                breathContainer.visibility = View.GONE

                if (hardBlock) {
                    // ブロック時は「見る」を場所ごと消してよい
                    openButton.visibility = View.GONE
                    if (mode != MODE_DAILY_LIMIT) {
                        questionText.text = "いまは、開かない時間。"
                        cancelButton.text = "閉じる"
                    }
                    return
                }

                // 待ち時間が明けて初めて「見る」が押せるようになる
                openButton.visibility = View.VISIBLE
                openButton.isEnabled = true

                // 摩擦の仕掛けを有効化。条件を満たすと onUnlocked が呼ばれる
                friction = FrictionController(
                    context = this@OverlayService,
                    root = view,
                    mode = config.friction
                ) { reason ->
                    reason?.let { Prefs(this@OverlayService).recordReason(packageName, it) }
                    unlockAndOpen(packageName, config, mode)
                }.also { it.attach() }
            }
        }.start()

        cancelButton.setOnClickListener {
            MaAccessibilityService.clearGrace(this, packageName)
            UsageAlarmScheduler.cancel(this, packageName)
            record(resisted = true)
            removeOverlay()
            goHome()
        }
    }

    /**
     * 摩擦の条件を満たしたときの処理。
     *
     * ここで扱う2つの「分」は役割がまったく違うので、混同しないよう分けて書く。
     *
     *  猶予（grace）… 「開く瞬間の介入」を抑える時間。
     *                  見ると決めた直後に同じ画面が何度も出ないようにするもの。
     *  閾値（threshold）… セッションが何分に達したら再び声をかけるか。
     *                  ユーザーが「◯分で再確認」として設定した値がこれにあたる。
     */
    private fun unlockAndOpen(packageName: String, config: Prefs.AppConfig, mode: String) {
        val graceMinutes = if (mode == MODE_USAGE) {
            // 「もう少しだけ」を選んだ直後に起動時の介入が出ないよう、短めに抑える
            (config.usageLimitMin / 2).coerceAtLeast(1)
        } else {
            config.graceMinutes
        }
        MaAccessibilityService.grantGrace(this, packageName, graceMinutes)

        if (config.usageLimitMin > 0) {
            val session = UsageTracker(this).getCurrentSessionMinutes(packageName)
            if (mode == MODE_USAGE) {
                // 「もう少しだけ」→ 今の時点から、上限の半分だけ延長する
                val extra = (config.usageLimitMin / 2).coerceAtLeast(1)
                UsageAlarmScheduler.schedule(
                    context = this,
                    packageName = packageName,
                    delayMinutes = extra,
                    thresholdMinutes = session + extra
                )
            } else {
                // 起動時 → セッションが usageLimitMin に達したら声をかける
                UsageAlarmScheduler.schedule(
                    context = this,
                    packageName = packageName,
                    delayMinutes = (config.usageLimitMin - session).coerceAtLeast(1),
                    thresholdMinutes = config.usageLimitMin
                )
            }
        }

        record(resisted = false)
        removeOverlay()
    }

    /**
     * フォアグラウンドサービス用の通知。
     * ユーザーの目に触れる時間はごく短いので、最小限の表示にする。
     * IMPORTANCE_MIN にすることで音もバナーも出さない。
     */
    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "一時停止",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "一時停止画面を表示している間だけ現れます"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ひと呼吸")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(Notification.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun formatDuration(minutes: Int): String {
        return if (minutes >= 60) {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h}時間" else "${h}時間${m}分"
        } else {
            "${minutes}分"
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun record(resisted: Boolean) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date())
        Prefs(this).recordResult(today, resisted)
        // 数字が変わったので、ホーム画面のウィジェットも更新する
        UsageWidgetProvider.requestUpdate(this)
    }

    /**
     * オーバーレイを閉じ、サービスも停止する。
     * ユーザーが選択を終えたときなど、役目が終わった場合に使う。
     */
    private fun removeOverlay() {
        removeOverlayViewOnly()
        currentTarget = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 表示中のビューだけを外し、サービスは動かしたままにする。
     * 別アプリ用のオーバーレイに差し替えるときに使う。
     * ここで stopSelf() まで呼ぶと、直後の表示要求が
     * 停止処理と競合して表示されないことがある。
     */
    private fun removeOverlayViewOnly() {
        timer?.cancel()
        timer = null
        overlayView?.findViewById<BreathingView>(R.id.breathingView)?.stop()
        friction?.detach()
        friction = null
        hideKeyboard()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // すでに外れている場合は無視
            }
        }
        overlayView = null
    }

    /** 理由入力や書き写しで出したキーボードを確実に閉じる */
    private fun hideKeyboard() {
        // ビューがウィンドウから外れた直後に呼ばれると、
        // トークンが無効になっていて例外になることがある。
        // キーボードが閉じられなかっただけでアプリを落とす理由はない。
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager ?: return
            overlayView?.windowToken?.let { token ->
                imm.hideSoftInputFromWindow(token, 0)
            }
        } catch (e: Exception) {
            // 閉じられなければそのままでよい
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        removeOverlay()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SESSION_MINUTES = "session_minutes"
        const val EXTRA_TODAY_MINUTES = "today_minutes"
        const val EXTRA_TODAY_LAUNCHES = "today_launches"
        const val MODE_LAUNCH = "launch"
        const val MODE_USAGE = "usage"
        const val MODE_DAILY_LIMIT = "daily_limit"
        /**
         * 動作中のサービス本体への参照。
         *
         * ＜静的参照を持つことについて＞
         * Androidコンポーネントの静的参照は一般にアンチパターンとされる。
         * ただしそれが問題になるのは主に Activity の場合で、
         * View 階層とウィンドウを丸ごと抱え込んだまま残ってしまうためだ。
         *
         * Service はそれらを保持しない。加えて、
         *  ・onCreate で設定し、onDestroy で必ず null にしている
         *  ・プロセスごと終了させられた場合は静的変数も一緒に消える
         * ため、参照が残り続けることはない。
         *
         * 状態だけを別クラスに持たせる案も検討したが、
         * 「オーバーレイを閉じる」には結局サービス本体を呼ぶ必要があり、
         * 参照の置き場所が変わるだけで実質は同じになる。
         * 層を増やして意図が分かりにくくなる方が損だと判断した。
         */
        @Volatile
        private var instance: OverlayService? = null

        /** いまオーバーレイが画面に出ているか */
        val isShowing: Boolean
            get() = instance?.overlayView != null

        /**
         * 表示中のオーバーレイを閉じる。
         *
         * ＜サービスを起動せずに閉じる理由＞
         * 以前はここで startForegroundService を呼んでいたが、
         * この関数は「対象外アプリに移った」ときに呼ばれるため、
         * ホーム画面・設定・LINE といった日常操作のたびに
         * サービス起動要求が飛ぶことになっていた。
         * オーバーレイが出ていない平常時ですら毎回である。
         *
         * SYSTEM_ALERT_WINDOW を持つアプリはバックグラウンド起動制限の
         * 例外扱いになるため即座に落ちるとは限らないが、
         *  ・無駄なサービス起動と通知のちらつき
         *  ・Android 15 以降で例外条件がさらに厳しくなる見込み
         * を考えると、依存すべきではない。
         *
         * そもそも「閉じる」のに新しくサービスを起動する必要はない。
         * 動作中のインスタンスに直接伝えれば済む。
         * 動いていなければ閉じる対象もないので、何もしない。
         *
         * @param stillForeground 閉じようとしている時点で、対象アプリが
         *   まだ前面に残っているか。画面分割などで対象アプリが見えたまま
         *   別アプリにフォーカスが移った場合、ここで閉じてしまうと
         *   介入をすり抜けられてしまうため、その場合は維持する。
         */
        fun dismiss(stillForeground: Boolean = false) {
            if (stillForeground) return
            val service = instance ?: return
            if (service.overlayView == null) return

            // アクセシビリティサービスのコールバックはメインスレッドで
            // 呼ばれるが、他の経路から呼ばれても安全なように明示する。
            // ビューの操作はメインスレッド以外から行うと例外になる。
            Handler(Looper.getMainLooper()).post {
                service.removeOverlay()
            }
        }

        /** 表示中のオーバーレイが対象としているアプリ */
        val showingTarget: String?
            get() = instance?.takeIf { it.overlayView != null }?.currentTarget

        private const val CHANNEL_ID = "ma_guard_pause"
        private const val NOTIFICATION_ID = 42
    }
}
