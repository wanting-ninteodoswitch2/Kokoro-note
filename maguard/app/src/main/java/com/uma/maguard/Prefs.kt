package com.uma.maguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * アプリ全体の設定と統計を一箇所で扱うクラス。
 *
 * ＜保存しているもの＞
 * ・対象アプリごとの設定（停止秒数・猶予分数・使用時間の上限）
 * ・時間帯別ルール（この時間帯は待ち時間を長くする、など）
 * ・日ごとの統計（見た回数・やめた回数）
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ＜このクラスの並行性とキャッシュについて＞
    //
    // Prefs は呼び出しのたびに new される（シングルトンではない）。
    // そのため以前 getAlarmId などに付けていた @Synchronized は
    // 「そのインスタンス」に対するロックにしかならず、
    // 別インスタンスから同時に呼ばれた場合の排他になっていなかった。
    // ロックは companion object 側に置き、全インスタンスで共有する。
    //
    // またアクセシビリティサービスは画面が切り替わるたびに
    // 設定を読むため、毎回 SharedPreferences から長いJSON文字列を
    // 読み出してパースすると、メインスレッドでの処理が積み重なって
    // 操作のもたつきにつながる。
    // パース済みの結果をメモリに保持し、保存時に更新する。

    // ---------- 対象アプリの設定 ----------

    /**
     * アプリごとの設定。
     * pauseSeconds:  一時停止画面を何秒表示するか
     * graceMinutes:  「見る」を選んだあと、何分間は再表示しないか
     * usageLimitMin: 開いたあと何分経ったら再度声をかけるか（0なら無効）
     */
    data class AppConfig(
        val pauseSeconds: Int = DEFAULT_PAUSE_SECONDS,
        val graceMinutes: Int = DEFAULT_GRACE_MINUTES,
        val usageLimitMin: Int = DEFAULT_USAGE_LIMIT_MIN,
        val dailyLimitMin: Int = DEFAULT_DAILY_LIMIT_MIN,
        val dailyLaunchLimit: Int = DEFAULT_DAILY_LAUNCH_LIMIT,
        val friction: FrictionMode = FrictionMode.NONE
    )

    /**
     * 「見る」を押すまでに挟む摩擦の種類。
     *
     * 待ち時間だけだと慣れて無意識に待てるようになるため、
     * ひと手間かける仕掛けを選べるようにする。
     *
     * NONE      … 摩擦なし（タップだけ）
     * LONG_PRESS… 3秒間の長押しが必要
     * REASON    … 開く理由を一言入力させる
     * TYPE_TEXT … 決まった文章を書き写させる（一番強い）
     */
    enum class FrictionMode {
        NONE, LONG_PRESS, REASON, TYPE_TEXT;

        fun label(): String = when (this) {
            NONE -> "なし"
            LONG_PRESS -> "長押し（3秒）"
            REASON -> "理由を書く"
            TYPE_TEXT -> "文章を書き写す"
        }

        /**
         * 説明文には「人前でやりにくくないか」も書いている。
         *
         * one sec の大規模調査（CHI 2024）では、最も使われなかった摩擦は
         * 「端末を回す」で、人前で目立つことが理由だと分析されている。
         * 摩擦は強ければ良いのではなく、日常のどこでも抵抗なく
         * 実行できるものでないと、そもそも使われなくなる。
         */
        fun description(): String = when (this) {
            NONE -> "タップするだけで開けます。呼吸の間だけを挟みます"
            LONG_PRESS -> "ボタンを3秒押し続けます。人前でも目立ちません"
            REASON -> "なぜ開くのかを一言入力します。惰性で開いていることに気づきやすくなります"
            TYPE_TEXT -> "指定した文章を書き写します。抑止力は最も強いですが、外出先では使いにくいかもしれません"
        }
    }

    fun getTargets(): Map<String, AppConfig> {
        targetsCache?.let { return it }
        return synchronized(lock) {
            // ロック取得までの間に他スレッドが用意していれば、それを使う
            targetsCache ?: parseTargets().also { targetsCache = it }
        }
    }

    private fun parseTargets(): Map<String, AppConfig> {
        val raw = prefs.getString(KEY_TARGETS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, AppConfig>()
            json.keys().forEach { pkg ->
                val obj = json.getJSONObject(pkg)
                result[pkg] = AppConfig(
                    pauseSeconds = obj.optInt("pauseSeconds", DEFAULT_PAUSE_SECONDS),
                    graceMinutes = obj.optInt("graceMinutes", DEFAULT_GRACE_MINUTES),
                    usageLimitMin = obj.optInt("usageLimitMin", DEFAULT_USAGE_LIMIT_MIN),
                    dailyLimitMin = obj.optInt("dailyLimitMin", DEFAULT_DAILY_LIMIT_MIN),
                    dailyLaunchLimit = obj.optInt("dailyLaunchLimit", DEFAULT_DAILY_LAUNCH_LIMIT),
                    friction = runCatching {
                        FrictionMode.valueOf(obj.optString("friction", "NONE"))
                    }.getOrDefault(FrictionMode.NONE)
                )
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveTargets(targets: Map<String, AppConfig>): Unit = synchronized(lock) {
        val json = JSONObject()
        targets.forEach { (pkg, config) ->
            json.put(pkg, JSONObject().apply {
                put("pauseSeconds", config.pauseSeconds)
                put("graceMinutes", config.graceMinutes)
                put("usageLimitMin", config.usageLimitMin)
                put("dailyLimitMin", config.dailyLimitMin)
                put("dailyLaunchLimit", config.dailyLaunchLimit)
                put("friction", config.friction.name)
            })
        }
        prefs.edit().putString(KEY_TARGETS, json.toString()).apply()
        targetsCache = targets.toMap()
    }

    fun getConfigFor(packageName: String): AppConfig? = getTargets()[packageName]

    /**
     * 時間帯別ルールを適用したあとの、実際に使う設定を返す。
     * 「今この瞬間、どう振る舞うべきか」を知りたいときはこれを呼ぶ。
     */
    fun getEffectiveConfig(packageName: String, now: Date = Date()): AppConfig? {
        val base = getConfigFor(packageName) ?: return null
        val rule = findActiveRule(now) ?: return base
        return base.copy(
            pauseSeconds = rule.pauseSeconds,
            graceMinutes = rule.graceMinutes
        )
    }

    // ---------- 時間帯別ルール ----------

    /**
     * 「この時間帯は、こう振る舞う」という設定。
     * 例：夜22時〜翌2時は30秒待たせる／朝6時〜8時は完全ブロック
     *
     * startMinute / endMinute は0時からの経過分（22:00なら1320）。
     * endMinute < startMinute の場合は日をまたぐ時間帯として扱う。
     * blocked が true なら「見る」ボタン自体を出さない。
     */
    data class TimeRule(
        val id: Long,
        val label: String,
        val startMinute: Int,
        val endMinute: Int,
        val pauseSeconds: Int,
        val graceMinutes: Int,
        val blocked: Boolean,
        val enabled: Boolean = true
    ) {
        fun contains(minuteOfDay: Int): Boolean {
            return when {
                // 開始と終了が同じ＝「終日」と解釈する。
                // ここを until のまま扱うと 00:00〜00:00 が
                // 「一日中」ではなく「該当なし」になり、
                // 設定したのに何も起きない、という分かりにくい状態になる。
                startMinute == endMinute -> true
                startMinute < endMinute -> minuteOfDay in startMinute until endMinute
                // 日をまたぐケース（例：22:00〜02:00）
                else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
            }
        }

        fun rangeLabelWithNote(): String =
            if (startMinute == endMinute) "終日" else rangeLabel()

        fun rangeLabel(): String {
            fun fmt(m: Int) = String.format(Locale.JAPAN, "%02d:%02d", m / 60, m % 60)
            return "${fmt(startMinute)}〜${fmt(endMinute)}"
        }
    }

    fun getTimeRules(): List<TimeRule> {
        timeRulesCache?.let { return it }
        return synchronized(lock) {
            timeRulesCache ?: parseTimeRules().also { timeRulesCache = it }
        }
    }

    private fun parseTimeRules(): List<TimeRule> {
        val raw = prefs.getString(KEY_TIME_RULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TimeRule(
                    id = o.optLong("id", i.toLong()),
                    label = o.optString("label", "ルール"),
                    startMinute = o.optInt("startMinute", 0),
                    endMinute = o.optInt("endMinute", 0),
                    pauseSeconds = o.optInt("pauseSeconds", DEFAULT_PAUSE_SECONDS),
                    graceMinutes = o.optInt("graceMinutes", DEFAULT_GRACE_MINUTES),
                    blocked = o.optBoolean("blocked", false),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTimeRules(rules: List<TimeRule>): Unit = synchronized(lock) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("label", r.label)
                put("startMinute", r.startMinute)
                put("endMinute", r.endMinute)
                put("pauseSeconds", r.pauseSeconds)
                put("graceMinutes", r.graceMinutes)
                put("blocked", r.blocked)
                put("enabled", r.enabled)
            })
        }
        prefs.edit().putString(KEY_TIME_RULES, arr.toString()).apply()
        timeRulesCache = rules.toList()
    }

    /**
     * 今の時刻に当てはまるルールを探す。
     * 複数該当した場合は、より厳しいもの（ブロック > 待ち時間が長い）を優先する。
     */
    fun findActiveRule(now: Date = Date()): TimeRule? {
        val cal = Calendar.getInstance().apply { time = now }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return getTimeRules()
            .filter { it.enabled && it.contains(minuteOfDay) }
            .maxWithOrNull(compareBy({ if (it.blocked) 1 else 0 }, { it.pauseSeconds }))
    }

    fun isBlockedNow(now: Date = Date()): Boolean = findActiveRule(now)?.blocked == true

    // ---------- 統計 ----------

    /**
     * 結果を1件記録する。
     * 形式: { "2026-08-07": { "resisted": 3, "opened": 1 }, ... }
     */
    fun recordResult(dateKey: String, resisted: Boolean): Unit = synchronized(lock) {
        val json = readJson(KEY_STATS)
        val dayObj = json.optJSONObject(dateKey) ?: JSONObject()
        val field = if (resisted) "resisted" else "opened"
        dayObj.put(field, dayObj.optInt(field, 0) + 1)
        json.put(dateKey, dayObj)

        prefs.edit().putString(KEY_STATS, json.toString()).apply()
    }

    data class DayStat(val date: String, val resisted: Int, val opened: Int)

    /**
     * 指定日の記録を返す。
     *
     * このメソッドは getStreak から連続日数を遡るために
     * 最大61回、getRecentStats からも7回まとめて呼ばれる。
     * 以前は呼ばれるたびに JSONObject(raw) で全体をパースし直していたため、
     * 連続日数を1つ表示するだけで数十回のパースが走っていた。
     * readJson のキャッシュを使えば、パースは最初の1回で済む。
     */
    fun getStatFor(dateKey: String): DayStat = synchronized(lock) {
        val dayObj = readJson(KEY_STATS).optJSONObject(dateKey)
            ?: return@synchronized DayStat(dateKey, 0, 0)
        DayStat(dateKey, dayObj.optInt("resisted", 0), dayObj.optInt("opened", 0))
    }

    /** 直近n日分の統計を、古い順に並べて返す（グラフ描画用） */
    fun getRecentStats(days: Int): List<DayStat> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        return (0 until days).map {
            val key = fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            getStatFor(key)
        }
    }

    fun getStreak(todayKey: String, previousKeys: List<String>): Int {
        var streak = 0
        if (getStatFor(todayKey).resisted > 0) streak++
        for (key in previousKeys) {
            if (getStatFor(key).resisted > 0) streak++ else break
        }
        return streak
    }

    // ---------- 摩擦の設定 ----------

    /** 書き写しモードで表示する文章。ユーザーが自由に設定できる */
    fun getTypeText(): String =
        prefs.getString(KEY_TYPE_TEXT, null) ?: DEFAULT_TYPE_TEXT

    fun setTypeText(text: String) {
        prefs.edit().putString(KEY_TYPE_TEXT, text.ifBlank { DEFAULT_TYPE_TEXT }).apply()
    }

    /**
     * 「理由」モードで入力された内容を記録する。
     * 後から振り返ると、自分がどんなときに開いているかが見える。
     */
    fun recordReason(packageName: String, reason: String): Unit = synchronized(lock) {
        if (reason.isBlank()) return@synchronized
        val arr = readJsonArray(KEY_REASONS)

        arr.put(JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("package", packageName)
            put("reason", reason.trim())
        })

        // 直近200件だけ保持する
        val trimmed = if (arr.length() > 200) {
            JSONArray().also { out ->
                for (i in (arr.length() - 200) until arr.length()) out.put(arr.get(i))
            }
        } else arr

        prefs.edit().putString(KEY_REASONS, trimmed.toString()).apply()
        // 件数を切り詰めた場合は別インスタンスになるため、
        // キャッシュも新しい方に差し替えておく
        jsonArrayCache[KEY_REASONS] = trimmed
    }

    data class ReasonEntry(val timestamp: Long, val packageName: String, val reason: String)

    fun getReasons(limit: Int = 50): List<ReasonEntry> = synchronized(lock) {
        return try {
            val arr = readJsonArray(KEY_REASONS)
            val out = mutableListOf<ReasonEntry>()
            for (i in (arr.length() - 1) downTo 0) {
                if (out.size >= limit) break
                val o = arr.getJSONObject(i)
                out.add(
                    ReasonEntry(
                        timestamp = o.optLong("ts"),
                        packageName = o.optString("package"),
                        reason = o.optString("reason")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- 猶予期限 ----------

    /**
     * 「見る」を選んだあと、いつまで起動時の介入を抑えるかを保存する。
     *
     * 以前はメモリ上の Map だけで持っていたため、OSにプロセスを
     * 落とされると猶予が消え、アプリに戻った瞬間にまた介入が出ていた。
     * バックグラウンドのサービスは普通に落とされるので、
     * ここは永続化しておかないと体験が壊れる。
     */
    fun setGraceUntil(packageName: String, untilMillis: Long): Unit = synchronized(lock) {
        val json = readJson(KEY_GRACE)
        json.put(packageName, untilMillis)
        prefs.edit().putString(KEY_GRACE, json.toString()).apply()
    }

    fun getGraceUntil(packageName: String): Long = synchronized(lock) {
        readJson(KEY_GRACE).optLong(packageName, 0L)
    }

    fun clearGrace(packageName: String): Unit = synchronized(lock) {
        val json = readJson(KEY_GRACE)
        json.remove(packageName)
        prefs.edit().putString(KEY_GRACE, json.toString()).apply()
    }

    /**
     * JSON をキャッシュ付きで読む。
     *
     * 猶予期限（KEY_GRACE）と起動回数（KEY_LAUNCHES）は、
     * アクセシビリティサービスから画面が切り替わるたびに読まれる。
     * 毎回 SharedPreferences から文字列を読んでパースすると、
     * メインスレッドでの処理が積み重なって操作のもたつきになる。
     *
     * 書き込みはすべてこのクラス経由で、返した JSONObject を
     * そのまま書き換えてから保存しているため、
     * キャッシュした参照と実データがずれることはない。
     */
    /** JSON配列版。readJson と同じくキャッシュを効かせる */
    private fun readJsonArray(key: String): JSONArray = synchronized(lock) {
        jsonArrayCache.getOrPut(key) {
            val raw = prefs.getString(key, null)
            try {
                if (raw != null) JSONArray(raw) else JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }
        }
    }

    private fun readJson(key: String): JSONObject = synchronized(lock) {
        jsonCache.getOrPut(key) {
            val raw = prefs.getString(key, null)
            try {
                if (raw != null) JSONObject(raw) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
        }
    }

    // ---------- 起動回数の自前カウント ----------

    /**
     * 起動を1件記録し、その日の通算回数を返す。
     *
     * ＜なぜ自前で数えるのか＞
     * UsageStatsManager の記録には反映のラグがあり、
     * 「いま開いた回」がまだ記録されていないことがある。
     * その状態で回数を読むと境界が1回ぶんずれ、
     * 「1日10回まで」が11回開けてしまう、といったことが起きる。
     *
     * アクセシビリティサービスは起動を即座に検知できるので、
     * そこで数えれば境界が確定する。UsageStats 側の値は
     * 「このアプリを止めていた間の起動」を拾うための下限として併用する。
     *
     * @param minGapMs この時間内の再前面化は同じ起動の続きとみなす
     */
    fun recordLaunch(packageName: String, dateKey: String, minGapMs: Long): Int = synchronized(lock) {
        val root = readJson(KEY_LAUNCHES)
        val day = root.optJSONObject(dateKey) ?: JSONObject()
        val entry = day.optJSONObject(packageName) ?: JSONObject()

        val now = System.currentTimeMillis()
        val hasPrevious = entry.has("lastAt")
        val lastAt = entry.optLong("lastAt", 0L)
        var count = entry.optInt("count", 0)

        // 初回、または前回の前面化から十分に間が空いていれば新しい起動として数える。
        // hasPrevious を見ないと、初回に lastAt=0 との差で判定してしまい
        // カウントが 0 のまま進んでしまう。
        if (!hasPrevious || now - lastAt > minGapMs) count++

        entry.put("count", count)
        entry.put("lastAt", now)
        day.put(packageName, entry)
        root.put(dateKey, day)

        // 古い日付を捨てる（直近7日分だけ残す）
        val keys = root.keys().asSequence().toList().sorted()
        if (keys.size > 7) {
            keys.take(keys.size - 7).forEach { root.remove(it) }
        }

        prefs.edit().putString(KEY_LAUNCHES, root.toString()).apply()
        return count
    }

    fun getLaunchCount(packageName: String, dateKey: String): Int = synchronized(lock) {
        val day = readJson(KEY_LAUNCHES).optJSONObject(dateKey)
            ?: return@synchronized 0
        day.optJSONObject(packageName)?.optInt("count", 0) ?: 0
    }

    // ---------- アラームID の採番 ----------

    /**
     * パッケージ名ごとに、重複しない安定した整数IDを割り当てる。
     *
     * AlarmManager の PendingIntent は requestCode で区別されるため、
     * アプリごとに別々の値が必要になる。packageName.hashCode() でも
     * 概ね動くが、String.hashCode() は 32bit で衝突がゼロではなく、
     * 衝突すると「別アプリのアラームを消してしまう」という
     * 発見しづらい不具合になる。
     *
     * ここでは採番結果を保存しておき、一度割り当てたIDは変わらないようにする。
     */
    fun getAlarmId(packageName: String): Int = synchronized(lock) {
        // AccessibilityService / BroadcastReceiver / UI の複数経路から
        // 呼ばれるため、採番中に別スレッドが割り込むと同じIDを
        // 2つのアプリに割り当ててしまう。
        // ロックは companion object 側に持たせ、Prefs のインスタンスが
        // 別でも同じ錠前を使うようにしている。
        val json = readJson(KEY_ALARM_IDS)

        if (json.has(packageName)) return json.getInt(packageName)

        // 既存の最大値 + 1 を新しいIDにする
        var maxId = ALARM_ID_BASE
        json.keys().forEach { key ->
            val v = json.optInt(key, ALARM_ID_BASE)
            if (v > maxId) maxId = v
        }
        val newId = maxId + 1

        json.put(packageName, newId)
        prefs.edit().putString(KEY_ALARM_IDS, json.toString()).apply()
        return newId
    }

    companion object {
        // 全インスタンスで共有する錠前。
        // Prefs は毎回 new されるため、インスタンス単位のロックでは
        // 排他制御にならない。
        private val lock = Any()

        // パース済み設定のメモリキャッシュ。
        // 保存時に必ず更新するので、実データとずれることはない。
        @Volatile
        private var targetsCache: Map<String, AppConfig>? = null

        @Volatile
        private var timeRulesCache: List<TimeRule>? = null

        // 猶予期限・起動回数など、頻繁に読まれるJSONの実体。
        // 読み書きはすべて lock の下で行う。
        private val jsonCache = mutableMapOf<String, JSONObject>()
        private val jsonArrayCache = mutableMapOf<String, JSONArray>()

        /** 設定を外部から書き換えた場合など、キャッシュを捨てたいときに使う */
        fun invalidateCache() = synchronized(lock) {
            targetsCache = null
            timeRulesCache = null
            jsonCache.clear()
            jsonArrayCache.clear()
        }

        const val PREFS_NAME = "ma_guard_prefs"
        private const val KEY_TYPE_TEXT = "type_text"
        private const val KEY_REASONS = "reasons_v1"
        private const val KEY_ALARM_IDS = "alarm_ids_v1"
        private const val KEY_GRACE = "grace_until_v1"
        private const val KEY_LAUNCHES = "launch_counts_v1"
        private const val ALARM_ID_BASE = 1000
        const val DEFAULT_TYPE_TEXT = "これを見ることは、いま本当に必要ではない。"
        private const val KEY_TARGETS = "targets_v5"
        private const val KEY_TIME_RULES = "time_rules_v1"
        private const val KEY_STATS = "stats_v1"
        const val DEFAULT_PAUSE_SECONDS = 8
        const val DEFAULT_GRACE_MINUTES = 5
        const val DEFAULT_USAGE_LIMIT_MIN = 15
        const val DEFAULT_DAILY_LIMIT_MIN = 0      // 0 = 無効
        const val DEFAULT_DAILY_LAUNCH_LIMIT = 0   // 0 = 無効
    }
}
