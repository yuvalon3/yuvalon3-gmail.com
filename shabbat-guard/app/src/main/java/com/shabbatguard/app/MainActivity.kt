package com.shabbatguard.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var liveText: TextView
    private lateinit var permText: TextView
    private lateinit var logText: TextView

    private lateinit var autoBox: CheckBox
    private lateinit var citySpinner: Spinner
    private lateinit var latEdit: EditText
    private lateinit var lonEdit: EditText
    private lateinit var beforeEdit: EditText
    private lateinit var afterEdit: EditText
    private lateinit var holidaysBox: CheckBox
    private lateinit var diasporaBox: CheckBox
    private lateinit var motionSecEdit: EditText
    private lateinit var delaySecEdit: EditText
    private lateinit var stillMinEdit: EditText
    private lateinit var walkThrEdit: EditText
    private lateinit var stillThrEdit: EditText
    private lateinit var tiltEdit: EditText
    private lateinit var proximityBox: CheckBox

    private val refresher = object : Runnable {
        override fun run() {
            refreshLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        loadValues()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.reschedule(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        root.addView(title("🛡️ שומר שבת", 24f))
        root.addView(note("בשבת ובחג האפליקציה נדרכת לבד: טלפון שמונח במקום אחד ${"‏"}5 דקות → צלצול רגיל. " +
            "טלפון שזז ברצף כמה שניות (הליכה) → אחרי השהיה קצרה עובר לרטט. בצאת השבת המצב המקורי חוזר."))

        statusText = text("", 16f).apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(card(statusText))

        liveText = text("", 14f).apply { typeface = Typeface.MONOSPACE }
        root.addView(card(liveText))

        root.addView(row(
            button("בדיקה ל-15 דק'") {
                GuardService.startTest(this, 15)
                toast("מצב בדיקה הופעל. שימו את הטלפון בכיס והסתובבו, ואז הניחו אותו על השולחן.")
            },
            button("עצירה") {
                GuardService.stop(this)
                handler.postDelayed({ refreshStatus() }, 500)
            },
        ))

        // ---- schedule ----
        root.addView(section("זמני הפעלה"))
        autoBox = CheckBox(this).apply { text = "הפעלה אוטומטית בכל שבת וחג" }
        root.addView(autoBox)

        root.addView(label("מיקום (לחישוב שקיעה)"))
        citySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Cities.ALL.map { it.name })
        }
        root.addView(citySpinner)
        latEdit = numberEdit(decimal = true, signed = true)
        lonEdit = numberEdit(decimal = true, signed = true)
        root.addView(row(labeled("קו רוחב", latEdit), labeled("קו אורך", lonEdit)))
        citySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val custom = position == Cities.CUSTOM_INDEX
                latEdit.isEnabled = custom
                lonEdit.isEnabled = custom
                if (!custom) {
                    latEdit.setText(Cities.ALL[position].lat.toString())
                    lonEdit.setText(Cities.ALL[position].lon.toString())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        beforeEdit = numberEdit()
        afterEdit = numberEdit()
        root.addView(row(
            labeled("דקות לפני שקיעה בערב שבת", beforeEdit),
            labeled("דקות אחרי שקיעה במוצ\"ש", afterEdit),
        ))
        root.addView(note("ברירת מחדל: 40 דק' לפני השקיעה (מכסה גם את מנהג ירושלים) ו-50 דק' אחרי (אחרי צאת הכוכבים). " +
            "לר\"ת אפשר לשים 75."))
        holidaysBox = CheckBox(this).apply { text = "גם בימים טובים (ר\"ה, יוה\"כ, סוכות, שמ\"ע, פסח, שבועות)" }
        diasporaBox = CheckBox(this).apply { text = "חוץ לארץ (יום טוב שני)" }
        root.addView(holidaysBox)
        root.addView(diasporaBox)

        // ---- motion ----
        root.addView(section("רגישות תנועה"))
        motionSecEdit = numberEdit()
        delaySecEdit = numberEdit()
        stillMinEdit = numberEdit()
        root.addView(row(
            labeled("שניות הליכה ברצף", motionSecEdit),
            labeled("השהיה לפני רטט (שנ')", delaySecEdit),
        ))
        root.addView(row(labeled("דקות מנוחה עד צלצול", stillMinEdit), View(this)))
        walkThrEdit = numberEdit(decimal = true)
        stillThrEdit = numberEdit(decimal = true)
        tiltEdit = numberEdit(decimal = true)
        root.addView(row(
            labeled("סף הליכה (m/s²)", walkThrEdit),
            labeled("סף מנוחה (m/s²)", stillThrEdit),
        ))
        root.addView(row(labeled("סף שינוי זווית (מעלות)", tiltEdit), View(this)))
        root.addView(note("הטלפון נחשב 'מונח' רק אם רמת התנועה בכל שנייה מתחת לסף המנוחה וגם הזווית שלו לא השתנתה. " +
            "כך גם ישיבה בבית הכנסת עם הטלפון בכיס לא תחזיר לצלצול. השתמשו בחלון הבדיקה למעלה כדי לראות את הערכים אצלכם."))
        proximityBox = CheckBox(this).apply {
            text = "לא לחזור לצלצול כשחיישן הקרבה מכוסה (בכיס). שימו לב: טלפון שמונח הפוך על השולחן יישאר ברטט."
        }
        root.addView(proximityBox)

        root.addView(button("💾 שמירה") { save() })

        // ---- permissions ----
        root.addView(section("הרשאות ואמינות"))
        permText = text("", 14f)
        root.addView(permText)
        root.addView(button("התעלמות מאופטימיזציית סוללה (חשוב!)") {
            startActivitySafe(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT >= 31) {
            root.addView(button("אישור התראות מדויקות (שעון מעורר)") {
                startActivitySafe(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }
        root.addView(button("גישה ל'נא לא להפריע' (מומלץ)") {
            startActivitySafe(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(button("הרשאת התראות") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }

        // ---- log ----
        root.addView(section("יומן פעילות"))
        logText = text("", 12f).apply { typeface = Typeface.MONOSPACE }
        root.addView(logText)
        root.addView(button("ניקוי יומן") { prefs.clearLog(); refreshStatus() })

        return ScrollView(this).apply { addView(root) }
    }

    private fun loadValues() {
        autoBox.isChecked = prefs.autoEnabled
        citySpinner.setSelection(prefs.cityIndex.coerceIn(0, Cities.ALL.lastIndex))
        latEdit.setText(prefs.lat.toString())
        lonEdit.setText(prefs.lon.toString())
        latEdit.isEnabled = prefs.cityIndex == Cities.CUSTOM_INDEX
        lonEdit.isEnabled = prefs.cityIndex == Cities.CUSTOM_INDEX
        beforeEdit.setText(prefs.minutesBefore.toString())
        afterEdit.setText(prefs.minutesAfter.toString())
        holidaysBox.isChecked = prefs.holidays
        diasporaBox.isChecked = prefs.diaspora
        motionSecEdit.setText(prefs.motionSeconds.toString())
        delaySecEdit.setText(prefs.switchDelaySeconds.toString())
        stillMinEdit.setText(prefs.stillMinutes.toString())
        walkThrEdit.setText(prefs.walkThreshold.toString())
        stillThrEdit.setText(prefs.stillThreshold.toString())
        tiltEdit.setText(prefs.tiltDegrees.toString())
        proximityBox.isChecked = prefs.useProximity
    }

    private fun save() {
        val lat = latEdit.text.toString().toDoubleOrNull()
        val lon = lonEdit.text.toString().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -66.0..66.0 || lon !in -180.0..180.0) {
            toast("קואורדינטות לא תקינות"); return
        }
        prefs.autoEnabled = autoBox.isChecked
        prefs.cityIndex = citySpinner.selectedItemPosition
        prefs.lat = lat
        prefs.lon = lon
        prefs.minutesBefore = intOf(beforeEdit, 40).coerceIn(0, 180)
        prefs.minutesAfter = intOf(afterEdit, 50).coerceIn(0, 180)
        prefs.holidays = holidaysBox.isChecked
        prefs.diaspora = diasporaBox.isChecked
        prefs.motionSeconds = intOf(motionSecEdit, 4).coerceIn(1, 60)
        prefs.switchDelaySeconds = intOf(delaySecEdit, 3).coerceIn(0, 120)
        prefs.stillMinutes = intOf(stillMinEdit, 5).coerceIn(1, 120)
        prefs.walkThreshold = floatOf(walkThrEdit, 1.2f).coerceIn(0.2f, 10f)
        prefs.stillThreshold = floatOf(stillThrEdit, 0.12f).coerceIn(0.01f, 3f)
        prefs.tiltDegrees = floatOf(tiltEdit, 5f).coerceIn(0.5f, 90f)
        prefs.useProximity = proximityBox.isChecked
        prefs.addLog("הגדרות נשמרו")
        Scheduler.reschedule(this)
        loadValues()
        refreshStatus()
        toast("נשמר. הגדרות תנועה חדשות נכנסות לתוקף בהפעלה הבאה.")
    }

    private fun refreshStatus() {
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("EEEE dd/MM HH:mm", Locale.forLanguageTag("he"))
        fun t(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

        statusText.text = when {
            prefs.activeUntil > System.currentTimeMillis() ->
                "✅ פעיל עכשיו: ${prefs.activeLabel}\nעד ${t(prefs.activeUntil)}"
            !prefs.autoEnabled -> "⏸ הפעלה אוטומטית כבויה"
            prefs.nextStart > 0 ->
                "⏰ הבא: ${prefs.nextLabel}\nנדרך: ${t(prefs.nextStart)}\nמסתיים: ${t(prefs.nextEnd)}"
            else -> "לא נמצא חלון קרוב"
        }

        val pm = getSystemService(PowerManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val lines = mutableListOf(
            "${mark(pm.isIgnoringBatteryOptimizations(packageName))} פטור מאופטימיזציית סוללה",
            "${mark(Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())} התראות מדויקות",
            "${mark(nm.isNotificationPolicyAccessGranted)} גישה ל'נא לא להפריע' (רשות)",
        )
        if (Build.VERSION.SDK_INT >= 33) {
            lines += "${mark(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)} התראות"
        }
        lines += "מצב צלצול נוכחי: ${Ringer.name(Ringer.current(this))}"
        permText.text = lines.joinToString("\n")

        logText.text = prefs.log.take(60).joinToString("\n").ifEmpty { "(ריק)" }
    }

    private fun refreshLive() {
        val l = GuardService.live
        liveText.text = if (l == null) {
            "השומר לא רץ כרגע.\nלחצו 'בדיקה' כדי לראות את חיישן התנועה בפעולה."
        } else {
            buildString {
                append("מצב: ${Ringer.name(l.mode)}  (${l.label})\n")
                append("רמת תנועה: ${"%.2f".format(l.level)}  זווית: ${"%.1f".format(l.tilt)}°\n")
                append("הליכה ברצף: ${l.movingRun} שנ' / ${prefs.motionSeconds}\n")
                if (l.pendingInSec != null) append("עובר לרטט בעוד ${l.pendingInSec} שנ'\n")
                if (l.mode == RingMode.VIBRATE) {
                    append("מונח כבר: ${l.stillForSec / 60}:${"%02d".format(l.stillForSec % 60)} / ${prefs.stillMinutes}:00\n")
                }
                if (prefs.useProximity) append("חיישן קרבה: ${if (l.proximityNear) "מכוסה" else "פנוי"}\n")
            }.trimEnd()
        }
        if (l == null && prefs.activeUntil > 0 && prefs.activeUntil < System.currentTimeMillis()) refreshStatus()
    }

    // ---------------- helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        textDirection = View.TEXT_DIRECTION_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun title(s: String, size: Float) = text(s, size).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(s: String) = text(s, 18f).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun label(s: String) = text(s, 14f).apply { setPadding(0, dp(8), 0, 0) }

    private fun note(s: String) = text(s, 13f).apply {
        alpha = 0.75f
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun card(v: View) = LinearLayout(this).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(0x14808080)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun labeled(s: String, edit: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(text(s, 13f))
        addView(edit)
    }

    private fun numberEdit(decimal: Boolean = false, signed: Boolean = false) = EditText(this).apply {
        var type = InputType.TYPE_CLASS_NUMBER
        if (decimal) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
        inputType = type
        textDirection = View.TEXT_DIRECTION_LTR
    }

    private fun intOf(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def
    private fun floatOf(e: EditText, def: Float) = e.text.toString().trim().toFloatOrNull() ?: def

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun startActivitySafe(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
