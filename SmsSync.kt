package com.smssync.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ===== BootReceiver.kt =====
/** ফোন রিস্টার্ট হলে আপনা-আপনি কাজ আবার চালু করে। */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.hasLink(context)) return
        SyncWorker.schedulePeriodic(context)
        SyncWorker.enqueue(context)
    }
}

// ===== Config.kt =====
object Config {
    // অ্যাপ খুললে এই PIN চাইবে। বদলাতে চাইলে শুধু এই লাইনটা বদলান।
    const val APP_PIN = "123456"

    // Firestore-এর যে নতুন কালেকশনে এসএমএস জমা হবে।
    const val COLLECTION = "sms_inbox"

    // একবারে কতগুলো এসএমএস সার্ভারে পাঠাবে।
    const val BATCH_SIZE = 100
}

// ===== Db.kt =====
data class SmsRow(
    val id: String,
    val sender: String,
    val body: String,
    val ts: Long,
    val source: String
) {
    companion object {
        // একই এসএমএসের আইডি সবসময় একই হয়, তাই দুবার পাঠালেও ডুপ্লিকেট তৈরি হয় না।
        fun make(sender: String, body: String, ts: Long, source: String): SmsRow {
            val raw = sender + "\u0000" + ts + "\u0000" + body
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            val id = digest.joinToString("") { "%02x".format(it) }.substring(0, 40)
            return SmsRow(id, sender, body, ts, source)
        }
    }
}

/** ফোনের ভেতরের অস্থায়ী লাইন: সব এসএমএস আগে এখানে জমে, তারপর সার্ভারে যায়। নেট না থাকলেও হারায় না। */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "sms.db", null, 1) {

    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL(
            "CREATE TABLE sms(" +
                "id TEXT PRIMARY KEY, " +
                "sender TEXT NOT NULL, " +
                "body TEXT NOT NULL, " +
                "ts INTEGER NOT NULL, " +
                "source TEXT NOT NULL, " +
                "sent INTEGER NOT NULL DEFAULT 0)"
        )
        d.execSQL("CREATE INDEX idx_sent ON sms(sent, ts)")
    }

    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}

    private fun values(r: SmsRow) = ContentValues().apply {
        put("id", r.id)
        put("sender", r.sender)
        put("body", r.body)
        put("ts", r.ts)
        put("source", r.source)
        put("sent", 0)
    }

    fun insertAll(rows: List<SmsRow>) {
        if (rows.isEmpty()) return
        val d = writableDatabase
        d.beginTransaction()
        try {
            for (r in rows) d.insertWithOnConflict("sms", null, values(r), SQLiteDatabase.CONFLICT_IGNORE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun pending(limit: Int): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        readableDatabase.rawQuery(
            "SELECT id, sender, body, ts, source FROM sms WHERE sent = 0 ORDER BY ts ASC LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(SmsRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)))
            }
        }
        return out
    }

    fun markSent(ids: List<String>) {
        if (ids.isEmpty()) return
        val d = writableDatabase
        d.beginTransaction()
        try {
            for (id in ids) d.execSQL("UPDATE sms SET sent = 1 WHERE id = ?", arrayOf<Any>(id))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** (মোট, পাঠানো হয়েছে) */
    fun stats(): Pair<Int, Int> {
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(sent), 0) FROM sms", null).use { c ->
            return if (c.moveToFirst()) Pair(c.getInt(0), c.getInt(1)) else Pair(0, 0)
        }
    }

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) {
                inst ?: Db(ctx).also { inst = it }
            }
    }
}

// ===== Firestore.kt =====
data class Link(val project: String, val key: String)

/** কোনো SDK বা তৃতীয় পক্ষের লাইব্রেরি ছাড়া সরাসরি Firestore REST API ব্যবহার করা হয়েছে। */
object Firestore {

    /**
     * এই দুই ভাবে লিংক বসানো যায়:
     *  1) https://firestore.googleapis.com/v1/projects/PROJECT/databases/(default)/documents?key=API_KEY
     *  2) PROJECT|API_KEY
     */
    fun parseLink(raw: String): Link? {
        val t = raw.trim()
        val p = Regex("projects/([^/\\s?]+)").find(t)?.groupValues?.get(1)
        val k = Regex("[?&]key=([^&\\s]+)").find(t)?.groupValues?.get(1)
        if (p != null && k != null) return Link(p, k)

        val parts = t.split(Regex("[|,\\s]+")).filter { it.isNotEmpty() }
        if (parts.size == 2) {
            return if (parts[0].startsWith("AIza")) Link(parts[1], parts[0]) else Link(parts[0], parts[1])
        }
        return null
    }

    private fun docsPath(l: Link) = "projects/${l.project}/databases/(default)/documents"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    private fun str(v: String) = JSONObject().put("stringValue", v)

    private fun call(method: String, url: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 40000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun explain(code: Int, text: String): String {
        val msg = try {
            JSONObject(text).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            400 -> "Invalid link or API key. ${msg.take(80)}".trim()
            403 -> "Permission denied. Check Firestore rules and API key."
            404 -> "Project or Firestore database not found."
            429 -> "Too many requests. Will retry."
            else -> "Server error $code. ${msg.take(80)}".trim()
        }
    }

    /** লিংক ঠিক আছে কিনা পরীক্ষা। ঠিক থাকলে null, নইলে ভুলের লেখা। */
    fun ping(l: Link): String? {
        return try {
            val url = "https://firestore.googleapis.com/v1/${docsPath(l)}/${Config.COLLECTION}?pageSize=1&key=${enc(l.key)}"
            val (code, text) = call("GET", url, null)
            if (code in 200..299) null else explain(code, text)
        } catch (e: IOException) {
            "No internet connection."
        } catch (e: Exception) {
            "Could not connect."
        }
    }

    /**
     * একসাথে অনেকগুলো এসএমএস লেখে। সফল হলে null, নইলে ভুলের লেখা।
     * updateMask ব্যবহার করায় একই এসএমএস আবার গেলেও আপনার সফটওয়্যারের যোগ করা অন্য ফিল্ড মুছে যায় না।
     */
    fun commit(l: Link, rows: List<SmsRow>): String? {
        return try {
            val base = docsPath(l)
            val maskFields = JSONArray(listOf("sender", "body", "smsTime", "smsTimeMs", "source", "device"))
            val writes = JSONArray()
            for (r in rows) {
                val fields = JSONObject()
                    .put("sender", str(r.sender))
                    .put("body", str(r.body))
                    .put("smsTime", JSONObject().put("timestampValue", iso(r.ts)))
                    .put("smsTimeMs", JSONObject().put("integerValue", r.ts.toString()))
                    .put("source", str(r.source))
                    .put("device", str(Build.MODEL ?: ""))
                val w = JSONObject()
                    .put(
                        "update",
                        JSONObject().put("name", "$base/${Config.COLLECTION}/${r.id}").put("fields", fields)
                    )
                    .put("updateMask", JSONObject().put("fieldPaths", maskFields))
                    .put(
                        "updateTransforms",
                        JSONArray().put(
                            JSONObject().put("fieldPath", "syncedAt").put("setToServerTime", "REQUEST_TIME")
                        )
                    )
                writes.put(w)
            }
            val url = "https://firestore.googleapis.com/v1/$base:commit?key=${enc(l.key)}"
            val (code, text) = call("POST", url, JSONObject().put("writes", writes).toString())
            if (code in 200..299) null else explain(code, text)
        } catch (e: IOException) {
            "No internet connection."
        } catch (e: Exception) {
            "Sync failed: ${e.message ?: "unknown"}"
        }
    }
}

// ===== MainActivity.kt =====
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    // 0 = PIN, 1 = লিংক বসানো, 2 = মূল স্ক্রিন
    var stage by remember { mutableStateOf(0) }

    when (stage) {
        0 -> PinScreen { stage = if (Prefs.hasLink(ctx)) 2 else 1 }
        1 -> SetupScreen { stage = 2 }
        else -> MainScreen {
            Prefs.clearLink(ctx)
            stage = 1
        }
    }
}

// ===== OldSms.kt =====
/** অ্যাপ ইন্সটলের আগে থেকে ফোনের ইনবক্সে থাকা সব এসএমএস ফোনের লাইনে তুলে আনে। */
object OldSms {
    fun importAll(ctx: Context): Int {
        val db = Db.get(ctx)
        val batch = ArrayList<SmsRow>()
        var count = 0
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "date ASC"
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val from = c.getString(0) ?: ""
                val body = c.getString(1) ?: ""
                val date = c.getLong(2)
                batch.add(SmsRow.make(from, body, date, "old"))
                count++
                if (batch.size >= 500) {
                    db.insertAll(batch)
                    batch.clear()
                }
            }
        }
        db.insertAll(batch)
        return count
    }
}

// ===== Prefs.kt =====
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun link(c: Context): Link? {
        val p = sp(c).getString("project", "") ?: ""
        val k = sp(c).getString("key", "") ?: ""
        return if (p.isNotEmpty() && k.isNotEmpty()) Link(p, k) else null
    }

    fun hasLink(c: Context) = link(c) != null

    fun saveLink(c: Context, l: Link) {
        sp(c).edit().putString("project", l.project).putString("key", l.key).apply()
    }

    fun clearLink(c: Context) {
        sp(c).edit().remove("project").remove("key").apply()
    }

    fun oldImported(c: Context) = sp(c).getBoolean("old_imported", false)
    fun setOldImported(c: Context) = sp(c).edit().putBoolean("old_imported", true).apply()

    fun lastSync(c: Context) = sp(c).getLong("last_sync", 0L)
    fun setLastSync(c: Context, t: Long) = sp(c).edit().putLong("last_sync", t).apply()

    fun lastError(c: Context) = sp(c).getString("last_error", "") ?: ""
    fun setLastError(c: Context, e: String) = sp(c).edit().putString("last_error", e).apply()
}

// ===== Screens.kt =====
private val SMS_PERMS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

private fun hasSmsPerm(c: Context) = SMS_PERMS.all {
    c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

private fun ignoringBattery(c: Context): Boolean {
    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(c.packageName)
}

/* ===================== PIN ===================== */
@Composable
fun PinScreen(onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Brand("SMS Sync")
            Spacer(Modifier.height(32.dp))
            Glass {
                Text("Enter PIN", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
                Spacer(Modifier.height(14.dp))
                Field(
                    value = pin,
                    onChange = { v ->
                        pin = v.filter { it.isDigit() }.take(Config.APP_PIN.length)
                        err = ""
                    },
                    placeholder = "••••••",
                    keyboard = KeyboardType.NumberPassword,
                    password = true,
                    center = true
                )
                Spacer(Modifier.height(10.dp))
                Text(err, color = C.err, fontSize = 15.sp, modifier = Modifier.height(22.dp))
                Spacer(Modifier.height(14.dp))
                Pill("Continue") {
                    if (pin == Config.APP_PIN) {
                        onOk()
                    } else {
                        err = "Invalid PIN."
                        pin = ""
                    }
                }
            }
        }
    }
}

/* ===================== Link setup ===================== */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Brand("SMS Sync")
            Spacer(Modifier.height(32.dp))
            Glass {
                Text("Database link", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
                Spacer(Modifier.height(14.dp))
                Field(
                    value = text,
                    onChange = { text = it; err = "" },
                    placeholder = "Paste your link here",
                    single = false,
                    minHeight = 110.dp
                )
                Spacer(Modifier.height(10.dp))
                Text(err, color = C.err, fontSize = 15.sp, modifier = Modifier.height(40.dp))
                Spacer(Modifier.height(10.dp))
                Pill(if (busy) "Please wait…" else "Save", enabled = !busy) {
                    val link = Firestore.parseLink(text)
                    if (link == null) {
                        err = "Link not recognised."
                    } else {
                        busy = true
                        err = ""
                        scope.launch {
                            val e = withContext(Dispatchers.IO) { Firestore.ping(link) }
                            busy = false
                            if (e == null) {
                                Prefs.saveLink(ctx, link)
                                onDone()
                            } else {
                                err = e
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ===================== Main ===================== */
@Composable
fun MainScreen(onChangeLink: () -> Unit) {
    val ctx = LocalContext.current
    var total by remember { mutableStateOf(0) }
    var sent by remember { mutableStateOf(0) }
    var perm by remember { mutableStateOf(hasSmsPerm(ctx)) }
    var battery by remember { mutableStateOf(ignoringBattery(ctx)) }
    var importing by remember { mutableStateOf(false) }
    var lastSync by remember { mutableStateOf(Prefs.lastSync(ctx)) }
    var lastErr by remember { mutableStateOf(Prefs.lastError(ctx)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perm = hasSmsPerm(ctx)
    }

    // প্রথমবার খুললে এসএমএসের অনুমতি চাওয়া
    LaunchedEffect(Unit) {
        if (!hasSmsPerm(ctx)) launcher.launch(SMS_PERMS)
    }

    // অনুমতি পেলে: কাজ চালু, আর পুরনো এসএমএস একবার তুলে আনা
    LaunchedEffect(perm) {
        if (perm) {
            SyncWorker.schedulePeriodic(ctx)
            if (!Prefs.oldImported(ctx)) {
                importing = true
                withContext(Dispatchers.IO) { OldSms.importAll(ctx) }
                Prefs.setOldImported(ctx)
                importing = false
            }
            SyncWorker.enqueue(ctx)
        }
    }

    // প্রতি ২ সেকেন্ডে হিসাব নতুন করে দেখানো
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) { Db.get(ctx).stats() }
            total = s.first
            sent = s.second
            perm = hasSmsPerm(ctx)
            battery = ignoringBattery(ctx)
            lastSync = Prefs.lastSync(ctx)
            lastErr = Prefs.lastError(ctx)
            delay(2000)
        }
    }

    val pending = total - sent
    val (status, statusColor) = when {
        !perm -> Pair("SMS access needed", C.warn)
        importing -> Pair("Importing old messages…", C.warn)
        lastErr.isNotEmpty() && pending > 0 -> Pair("Waiting to retry", C.err)
        pending > 0 -> Pair("Sending…", C.warn)
        else -> Pair("All synced", C.ok)
    }
    val timeText = if (lastSync == 0L) "—" else SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date(lastSync))

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Brand("SMS Sync")
            Spacer(Modifier.height(28.dp))
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(statusColor)
                    Spacer(Modifier.width(10.dp))
                    Text(status, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
                }
                Spacer(Modifier.height(12.dp))
                StatRow("Total SMS", total.toString())
                StatRow("Sent", sent.toString())
                StatRow("Waiting", pending.toString())
                StatRow("Last sync", timeText)
                if (lastErr.isNotEmpty() && pending > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(lastErr, color = C.err, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(22.dp))

            if (!perm) {
                Pill("Allow SMS access") { launcher.launch(SMS_PERMS) }
                Spacer(Modifier.height(12.dp))
                Pill("Open app settings") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "If the permission box does not appear: App settings → ⋮ (top right) → Allow restricted settings, then try again.",
                    fontSize = 13.sp,
                    color = C.gray,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!battery) {
                Pill("Keep running in background") {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Pill("Sync now") { SyncWorker.enqueue(ctx) }

            Spacer(Modifier.height(8.dp))
            LinkText("Change link", onChangeLink)
        }
    }
}

// ===== SmsReceiver.kt =====
/** নতুন এসএমএস আসামাত্র ধরে, ফোনে জমা রাখে, আর পাঠানোর কাজ শুরু করে। কোনো ফিল্টার নেই। */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.hasLink(context)) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // লম্বা এসএমএস কয়েক টুকরায় আসে, প্রেরক অনুযায়ী জোড়া লাগানো হয়।
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (p in parts) {
            val from = p.originatingAddress ?: ""
            bySender.getOrPut(from) { StringBuilder() }.append(p.messageBody ?: "")
        }

        val now = System.currentTimeMillis()
        val rows = bySender.map { (from, body) -> SmsRow.make(from, body.toString(), now, "live") }
        Db.get(context).insertAll(rows)
        SyncWorker.enqueue(context)
    }
}

// ===== SyncWorker.kt =====
/** ফোনে জমে থাকা সব এসএমএস Firestore-এ পাঠায়। নেট না থাকলে নেট আসার পর আবার চেষ্টা করে। */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val link = Prefs.link(c) ?: return@withContext Result.success()
        val db = Db.get(c)

        while (true) {
            if (isStopped) return@withContext Result.retry()
            val rows = db.pending(Config.BATCH_SIZE)
            if (rows.isEmpty()) break

            val err = Firestore.commit(link, rows)
            if (err != null) {
                Prefs.setLastError(c, err)
                return@withContext Result.retry()
            }
            db.markSent(rows.map { it.id })
            Prefs.setLastSync(c, System.currentTimeMillis())
            Prefs.setLastError(c, "")
        }
        Result.success()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** এখনই পাঠানো শুরু করো (নেট থাকলে)। */
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork("sync_now", ExistingWorkPolicy.REPLACE, req)
        }

        /** নিরাপত্তার জন্য প্রতি ১৫ মিনিটে একবার করে বাকি থাকা এসএমএস পাঠানোর চেষ্টা। */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

// ===== Ui.kt =====
/** রেফারেন্স ছবির মতো ধূসর-সাদা গ্লাস থিম। */
object C {
    val bgTop = Color(0xFFF7F8FA)
    val bgMid = Color(0xFFE3E6EA)
    val bgBot = Color(0xFFFFFFFF)
    val ink = Color(0xFF111111)
    val gray = Color(0xFF6B7078)
    val placeholder = Color(0xFF9AA0A6)
    val field = Color(0xFFF3F4F6)
    val btn = Color(0xFFFAFAFA)
    val err = Color(0xFFB3261E)
    val ok = Color(0xFF1E8E3E)
    val warn = Color(0xFFB26A00)
    val shadowA = Color(0x1A000000)
    val shadowS = Color(0x26000000)
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(C.bgTop, C.bgMid, C.bgBot))),
        content = content
    )
}

@Composable
fun Brand(title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(22.dp)
        Box(
            Modifier
                .size(64.dp)
                .shadow(10.dp, shape, ambientColor = C.shadowA, spotColor = C.shadowS)
                .clip(shape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_bubble), contentDescription = null, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = C.ink)
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = C.shadowA, spotColor = C.shadowS)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.6f))
            .border(1.5.dp, Color.White, shape)
            .padding(24.dp),
        content = content
    )
}

@Composable
fun Pill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(10.dp, shape, ambientColor = C.shadowA, spotColor = C.shadowS)
            .clip(shape)
            .background(C.btn)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) C.ink else C.gray
        )
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(12.dp),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        color = C.gray
    )
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    single: Boolean = true,
    center: Boolean = false,
    minHeight: Dp = 0.dp
) {
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = single,
        textStyle = TextStyle(
            color = C.ink,
            fontSize = if (center) 24.sp else 16.sp,
            textAlign = if (center) TextAlign.Center else TextAlign.Start
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation('•') else VisualTransformation.None,
        cursorBrush = SolidColor(C.ink),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(shape)
                    .background(C.field)
                    .border(1.dp, Color.White, shape)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                contentAlignment = if (center) Alignment.Center else Alignment.TopStart
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = C.placeholder, fontSize = if (center) 24.sp else 16.sp)
                }
                inner()
            }
        }
    )
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = C.ink) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 17.sp, color = C.gray)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
fun Dot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
