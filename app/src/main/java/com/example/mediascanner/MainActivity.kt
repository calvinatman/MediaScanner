package com.example.mediascanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    class M(val path: String, val type: Int, val size: Long, val mod: Long) {
        val name: String get() = path.substringAfterLast('/')
        val dir: String get() = path.substringBeforeLast('/')
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private var dark = false
    private var bg = 0
    private var card = 0
    private var tx = 0
    private var sub = 0
    private var line = 0
    private val accent = 0xFFF5601A.toInt()
    private val danger = 0xFFD93025.toInt()
    private val typeColor = intArrayOf(0xFF378ADD.toInt(), 0xFF7F77DD.toInt(), 0xFF1D9E75.toInt())
    private val typeName = arrayOf("Photos", "Videos", "Audio")
    private val typeGlyph = arrayOf("IMG", "VID", "AUD")
    private val sortNames = arrayOf("Date", "Size", "Name")

    private var lists = arrayOf(ArrayList<M>(), ArrayList<M>(), ArrayList<M>())
    private val hidden = LinkedHashMap<String, ArrayList<M>>()
    private var scanning = false
    private var scanned = false
    private var asked = false

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private val navItems = ArrayList<TextView>()
    private val back = ArrayList<() -> Unit>()
    private var cur: () -> Unit = {}
    private var tabSel = 0

    private var listTab = 0
    private var query = ""
    private var sortMode = 0
    private var gridMode = true
    private var selecting = false
    private val selected = HashSet<String>()
    private var shown: List<M> = emptyList()

    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("media_scanner", MODE_PRIVATE)
        val mode = prefs.getString("theme", "System")
        val sys = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = when (mode) {
            "Dark" -> true
            "Light" -> false
            else -> sys
        }
        setTheme(if (dark) R.style.AppDark else R.style.AppLight)
        super.onCreate(savedInstanceState)

        bg = if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        card = if (dark) 0xFF1E1E1E.toInt() else 0xFFF4F4F2.toInt()
        tx = if (dark) 0xFFECECEC.toInt() else 0xFF1B1B1A.toInt()
        sub = if (dark) 0xFF9A9A96.toInt() else 0xFF6B6B66.toInt()
        line = if (dark) 0xFF3A3A38.toInt() else 0xFFDADAD5.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (!dark) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        navBar = col()
        val ln = View(this)
        ln.setBackgroundColor(line)
        navBar.addView(ln, LinearLayout.LayoutParams(MATCH, 1))
        val nr = LinearLayout(this)
        nr.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Home", "Folders", "Settings")
        for (i in 0..2) {
            val t = tv(names[i], 12f, sub, false)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(12), 0, dp(12))
            t.setOnClickListener { showRoot(i) }
            navItems.add(t)
            nr.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        navBar.addView(nr)
        root.addView(navBar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        val start = prefs.getInt("startTab", 0)
        prefs.edit().putInt("startTab", 0).apply()
        showRoot(start)
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (!scanned && !scanning) scan()
        } else if (!asked) {
            asked = true
            requestAccess()
        }
        if (back.isEmpty()) cur()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAccess()) scan()
    }

    override fun onBackPressed() {
        goBack()
    }

    private fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName)))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tint(c: Int): Int = Color.argb(45, Color.red(c), Color.green(c), Color.blue(c))

    private fun rounded(fill: Int, r: Int, stroke: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(r).toFloat()
        if (stroke != 0) d.setStroke(1, stroke)
        return d
    }

    private fun tv(t: String, sp: Float = 14f, color: Int = tx, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun col(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun row(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    private fun cardBox(): LinearLayout {
        val l = col()
        l.background = rounded(card, 12, 0)
        l.setPadding(dp(14), dp(12), dp(14), dp(12))
        return l
    }

    private fun lpTop(n: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MATCH, WRAP)
        p.topMargin = dp(n)
        return p
    }

    private fun bl(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, WRAP, 1f)
        p.setMargins(dp(4), 0, dp(4), 0)
        return p
    }

    private fun cardLp(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, WRAP, 1f)
        p.setMargins(dp(5), dp(5), dp(5), dp(5))
        return p
    }

    private fun chip(t: String, on: Boolean, c: Int = 0, click: () -> Unit): TextView {
        val fg = if (c != 0) c else if (on) accent else sub
        val v = tv(t, 13f, fg, on || c != 0)
        v.setPadding(dp(14), dp(7), dp(14), dp(7))
        v.background = rounded(if (on) tint(accent) else card, 16, if (c != 0) c else if (on) accent else line)
        v.setOnClickListener { click() }
        return v
    }

    private fun btn(t: String, color: Int = tx, border: Int = line, click: () -> Unit): TextView {
        val v = tv(t, 14f, color, true)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(8), dp(12), dp(8), dp(12))
        v.background = rounded(card, 10, border)
        v.setOnClickListener { click() }
        return v
    }

    private fun label(t: String): TextView {
        val v = tv(t, 13f, sub)
        v.setPadding(0, dp(18), 0, dp(6))
        return v
    }

    private fun header(title: String, vararg actions: View): LinearLayout {
        val h = row()
        h.setPadding(dp(4), dp(8), dp(12), dp(8))
        val b = tv("←", 24f, tx, false)
        b.setPadding(dp(12), 0, dp(12), 0)
        b.setOnClickListener { goBack() }
        h.addView(b)
        val t = tv(title, 18f, tx, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        for (a in actions) {
            val p = LinearLayout.LayoutParams(WRAP, WRAP)
            p.leftMargin = dp(8)
            h.addView(a, p)
        }
        return h
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun setScreen(v: View) {
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun updateNav() {
        for (i in 0..2) {
            val on = i == tabSel
            navItems[i].setTextColor(if (on) accent else sub)
            navItems[i].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun showRoot(i: Int) {
        back.clear()
        tabSel = i
        selecting = false
        selected.clear()
        navBar.visibility = View.VISIBLE
        cur = when (i) {
            0 -> this::showHome
            1 -> this::showFolders
            else -> this::showSettings
        }
        cur()
        updateNav()
    }

    private fun open(render: () -> Unit) {
        back.add(cur)
        cur = render
        navBar.visibility = View.GONE
        render()
    }

    private fun goBack() {
        if (selecting) {
            selecting = false
            selected.clear()
            cur()
            return
        }
        if (back.isNotEmpty()) {
            cur = back.removeAt(back.size - 1)
            navBar.visibility = if (back.isEmpty()) View.VISIBLE else View.GONE
            cur()
        } else {
            finish()
        }
    }

    private fun openList(title: String, tabs: Boolean, src: () -> List<M>) {
        query = ""
        selecting = false
        selected.clear()
        open { showList(title, tabs, src) }
    }

    private fun openTypeList(t: Int) {
        listTab = t
        openList("Files", true) { emptyList() }
    }

    private fun typeOf(name: String): Int {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> 0
            "mp4", "mkv", "3gp", "avi", "mov", "webm", "m4v" -> 1
            "mp3", "m4a", "aac", "wav", "ogg", "opus", "amr", "flac" -> 2
            else -> -1
        }
    }

    private fun groupOf(path: String): String? {
        val l = path.lowercase()
        if (l.contains("/.trashed") || l.contains("/.trash")) return "Gallery trash"
        if (l.contains("/.thumbnails")) return "Thumbnails"
        if (l.contains("/.statuses")) return "WhatsApp statuses"
        if (l.contains("/cache/") || l.contains("/.cache/")) return "App cache"
        for (s in l.split('/')) {
            if (s.startsWith(".") && s.length > 1) return "Other hidden folders"
        }
        return null
    }

    private fun allItems(): List<M> {
        val l = ArrayList<M>()
        for (x in lists) l.addAll(x)
        for (x in hidden.values) l.addAll(x)
        return l
    }

    private fun scan() {
        if (scanning || !hasAccess()) return
        scanning = true
        val scanHid = prefs.getBoolean("scanHidden", true)
        if (back.isEmpty()) cur()
        Thread {
            val res = arrayOf(ArrayList<M>(), ArrayList<M>(), ArrayList<M>())
            val hid = LinkedHashMap<String, ArrayList<M>>()
            try {
                val root = Environment.getExternalStorageDirectory()
                root.walkTopDown().onFail { _, _ -> }.forEach { f ->
                    if (f.isFile) {
                        val t = typeOf(f.name)
                        if (t >= 0) {
                            val m = M(f.absolutePath, t, f.length(), f.lastModified())
                            val g = groupOf(m.path)
                            if (g == null) {
                                res[t].add(m)
                            } else if (scanHid) {
                                hid.getOrPut(g) { ArrayList() }.add(m)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
            }
            for (l in res) l.sortByDescending { it.mod }
            runOnUiThread {
                lists = res
                hidden.clear()
                hidden.putAll(hid)
                scanning = false
                scanned = true
                if (back.isEmpty()) cur()
            }
        }.start()
    }

    private fun decode(path: String, target: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeFile(path, o2)
    }

    private fun frame(path: String, target: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        var out: Bitmap? = null
        try {
            r.setDataSource(path)
            val b = r.getFrameAtTime(0L)
            if (b != null) {
                out = if (b.width <= target) b else Bitmap.createScaledBitmap(
                    b, target, (b.height.toLong() * target / b.width).toInt().coerceAtLeast(1), true
                )
            }
        } catch (e: Exception) {
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
        return out
    }

    private fun loadThumb(iv: ImageView, m: M, target: Int) {
        if (m.type == 2) return
        val key = m.path + "#" + target
        iv.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        pool.execute {
            val b = try {
                if (m.type == 0) decode(m.path, target) else frame(m.path, target)
            } catch (t: Throwable) {
                null
            }
            if (b != null) {
                cache.put(key, b)
                runOnUiThread {
                    if (iv.tag == key) iv.setImageBitmap(b)
                }
            }
        }
    }

    private fun thumb(m: M): FrameLayout {
        val fr = FrameLayout(this)
        fr.background = rounded(tint(typeColor[m.type]), 8, 0)
        fr.clipToOutline = true
        val g = tv(typeGlyph[m.type], 12f, typeColor[m.type], true)
        fr.addView(g, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        fr.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        loadThumb(iv, m, 256)
        return fr
    }

    private fun checkView(on: Boolean): TextView {
        val v = tv(if (on) "✓" else "", 14f, Color.WHITE, true)
        v.gravity = Gravity.CENTER
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(if (on) accent else Color.argb(110, 0, 0, 0))
        d.setStroke(dp(2), Color.WHITE)
        v.background = d
        return v
    }

    private fun gridCell(m: M, on: Boolean, cw: Int): View {
        val c = col()
        val fr = thumb(m)
        if (selecting) {
            val p = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START)
            p.setMargins(dp(4), dp(4), 0, 0)
            fr.addView(checkView(on), p)
        }
        fr.alpha = if (on) 0.6f else 1f
        c.addView(fr, LinearLayout.LayoutParams(cw, cw))
        val n = tv(m.name, 11f, sub)
        n.maxLines = 1
        n.ellipsize = TextUtils.TruncateAt.MIDDLE
        c.addView(n, LinearLayout.LayoutParams(cw, WRAP))
        return c
    }

    private fun listRow(m: M, on: Boolean): View {
        val r = row()
        r.setPadding(0, dp(6), 0, dp(6))
        if (selecting) {
            val p = LinearLayout.LayoutParams(dp(24), dp(24))
            p.rightMargin = dp(10)
            r.addView(checkView(on), p)
        }
        r.addView(thumb(m), LinearLayout.LayoutParams(dp(52), dp(52)))
        val t = col()
        t.setPadding(dp(12), 0, 0, 0)
        val n = tv(m.name, 14f, tx, true)
        n.maxLines = 1
        n.ellipsize = TextUtils.TruncateAt.MIDDLE
        val p = tv(m.dir, 12f, sub)
        p.maxLines = 1
        p.ellipsize = TextUtils.TruncateAt.START
        val i = tv(Formatter.formatFileSize(this, m.size) + " | " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.mod)), 12f, sub)
        t.addView(n)
        t.addView(p)
        t.addView(i)
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        return r
    }

    inner class FileAdapter(private val grid: Boolean, private val cw: Int) : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = shown[position]
            val on = selected.contains(m.path)
            return if (grid) gridCell(m, on, cw) else listRow(m, on)
        }
    }

    private fun showHome() {
        val sv = ScrollView(this)
        val c = col()
        c.setPadding(dp(16), dp(12), dp(16), dp(16))
        sv.addView(c)

        val head = row()
        val ic = ImageView(this)
        ic.setImageResource(R.mipmap.ic_launcher)
        head.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
        val ttl = tv("Media Scanner", 20f, tx, true)
        ttl.setPadding(dp(10), 0, 0, 0)
        head.addView(ttl)
        c.addView(head)

        val st = row()
        st.setPadding(0, dp(14), 0, dp(10))
        val stc = col()
        val total = lists.sumOf { it.size } + hidden.values.sumOf { it.size }
        stc.addView(tv(if (scanning) "Scanning storage…" else if (!hasAccess()) "Access needed" else "Scan complete", 15f, tx, true))
        stc.addView(tv(if (scanning) "Looking through all folders" else total.toString() + " files found offline", 12f, sub))
        st.addView(stc, LinearLayout.LayoutParams(0, WRAP, 1f))
        st.addView(chip("Rescan", false) { if (hasAccess()) scan() else requestAccess() })
        c.addView(st)

        if (!hasAccess()) {
            val w = cardBox()
            w.addView(tv("All files access is needed to scan your storage.", 13f, tx))
            w.addView(btn("Allow access") { requestAccess() }, lpTop(10))
            c.addView(w, lpTop(0))
        }

        val hiddenCount = hidden.values.sumOf { it.size }
        val hiddenBytes = hidden.values.sumOf { g -> g.sumOf { it.size } }
        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(statCard("Photos", lists[0].size, lists[0].sumOf { it.size }, typeColor[0]) { openTypeList(0) }, cardLp())
        r1.addView(statCard("Videos", lists[1].size, lists[1].sumOf { it.size }, typeColor[1]) { openTypeList(1) }, cardLp())
        c.addView(r1)
        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(statCard("Audio", lists[2].size, lists[2].sumOf { it.size }, typeColor[2]) { openTypeList(2) }, cardLp())
        r2.addView(statCard("Hidden and leftover", hiddenCount, hiddenBytes, 0xFFEF9F27.toInt()) { open { showHidden() } }, cardLp())
        c.addView(r2)

        val sc = cardBox()
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val tot = stat.totalBytes
        val free = stat.availableBytes
        val used = tot - free
        val sh = row()
        sh.addView(tv("Device storage", 15f, tx, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.addView(tv(Formatter.formatFileSize(this, tot) + " total", 12f, sub))
        sc.addView(sh)
        val pct = if (tot > 0) used.toFloat() * 100f / tot.toFloat() else 0f
        val track = LinearLayout(this)
        track.background = rounded(line, 5, 0)
        val fill = View(this)
        fill.background = rounded(accent, 5, 0)
        track.addView(fill, LinearLayout.LayoutParams(0, MATCH, pct))
        track.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 100f - pct))
        val tlp = LinearLayout.LayoutParams(MATCH, dp(10))
        tlp.setMargins(0, dp(12), 0, dp(12))
        sc.addView(track, tlp)
        val ur = LinearLayout(this)
        ur.orientation = LinearLayout.HORIZONTAL
        val left = col()
        left.addView(tv("Occupied", 12f, sub))
        left.addView(tv(Formatter.formatFileSize(this, used), 16f, tx, true))
        val right = col()
        right.gravity = Gravity.END
        val av = tv("Available", 12f, sub)
        av.gravity = Gravity.END
        val avv = tv(Formatter.formatFileSize(this, free), 16f, tx, true)
        avv.gravity = Gravity.END
        right.addView(av, LinearLayout.LayoutParams(MATCH, WRAP))
        right.addView(avv, LinearLayout.LayoutParams(MATCH, WRAP))
        ur.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        ur.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
        sc.addView(ur)
        val sp = LinearLayout.LayoutParams(MATCH, WRAP)
        sp.setMargins(dp(5), dp(5), dp(5), dp(5))
        c.addView(sc, sp)

        c.addView(tv("Hidden and leftover shows trash, thumbnails, status media, cache and hidden folders where deleted-looking files often remain.", 12f, sub).apply { setPadding(dp(5), dp(8), dp(5), 0) })
        setScreen(sv)
    }

    private fun statCard(title: String, count: Int, bytes: Long, color: Int, click: () -> Unit): View {
        val b = cardBox()
        val t = row()
        val dot = View(this)
        dot.background = rounded(color, 5, 0)
        t.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        val tt = tv(title, 13f, sub)
        tt.setPadding(dp(6), 0, 0, 0)
        t.addView(tt)
        b.addView(t)
        b.addView(tv(count.toString(), 22f, tx, true))
        b.addView(tv(Formatter.formatFileSize(this, bytes), 12f, sub))
        b.setOnClickListener { click() }
        return b
    }

    private fun showList(title: String, tabs: Boolean, src: () -> List<M>) {
        val source: () -> List<M> = { if (tabs) lists[listTab] else src() }
        val useGrid = gridMode && !(tabs && listTab == 2)
        val cw = (resources.displayMetrics.widthPixels - dp(32) - dp(16)) / 3

        val root = col()
        val viewBtn = chip(if (gridMode) "List" else "Grid", false) {
            gridMode = !gridMode
            showList(title, tabs, src)
        }
        val selBtn = chip(if (selecting) "Cancel" else "Select", selecting) {
            selecting = !selecting
            selected.clear()
            showList(title, tabs, src)
        }
        root.addView(header(title, viewBtn, selBtn))

        if (tabs) {
            val tr = row()
            tr.setPadding(dp(16), 0, dp(16), dp(8))
            for (i in 0..2) {
                val p = LinearLayout.LayoutParams(WRAP, WRAP)
                p.rightMargin = dp(8)
                tr.addView(chip(typeName[i], i == listTab) {
                    listTab = i
                    selected.clear()
                    showList(title, tabs, src)
                }, p)
            }
            root.addView(tr)
        }

        val sr = row()
        sr.setPadding(dp(16), 0, dp(16), dp(4))
        val et = EditText(this)
        et.hint = "Search files"
        et.setText(query)
        et.setSingleLine(true)
        et.textSize = 14f
        et.setTextColor(tx)
        et.setHintTextColor(sub)
        et.background = rounded(card, 8, line)
        et.setPadding(dp(12), dp(8), dp(12), dp(8))
        sr.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sortBtn = chip("Sort: " + sortNames[sortMode], false) { }
        val sp = LinearLayout.LayoutParams(WRAP, WRAP)
        sp.leftMargin = dp(8)
        sr.addView(sortBtn, sp)
        root.addView(sr)

        val countTv = tv("", 12f, sub)
        countTv.setPadding(dp(16), dp(4), dp(16), 0)
        root.addView(countTv)

        val gv = GridView(this)
        gv.numColumns = if (useGrid) 3 else 1
        gv.horizontalSpacing = dp(8)
        gv.verticalSpacing = dp(8)
        gv.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        gv.setSelector(ColorDrawable(Color.TRANSPARENT))
        gv.setPadding(dp(16), dp(8), dp(16), dp(8))
        gv.clipToPadding = false
        val adapter = FileAdapter(useGrid, cw)
        gv.adapter = adapter
        root.addView(gv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val barView = row()
        barView.background = rounded(card, 0, 0)
        barView.setPadding(dp(16), dp(10), dp(16), dp(10))
        val barCount = tv("", 13f, tx)
        barView.addView(barCount, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(barView, LinearLayout.LayoutParams(MATCH, WRAP))

        fun refresh() {
            val q = query.trim().lowercase()
            var l: List<M> = source().filter { q.isEmpty() || it.name.lowercase().contains(q) }
            l = when (sortMode) {
                1 -> l.sortedByDescending { it.size }
                2 -> l.sortedBy { it.name.lowercase() }
                else -> l.sortedByDescending { it.mod }
            }
            shown = l
            adapter.notifyDataSetChanged()
            countTv.text = l.size.toString() + " files"
            barView.visibility = if (selecting) View.VISIBLE else View.GONE
            barCount.text = selected.size.toString() + " selected"
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString() ?: ""
                refresh()
            }
        })
        sortBtn.setOnClickListener {
            sortMode = (sortMode + 1) % 3
            sortBtn.text = "Sort: " + sortNames[sortMode]
            refresh()
        }
        gv.setOnItemClickListener { _, _, pos, _ ->
            val m = shown[pos]
            if (selecting) {
                if (!selected.remove(m.path)) selected.add(m.path)
                refresh()
            } else {
                open { showDetail(m) }
            }
        }
        gv.setOnItemLongClickListener { _, _, pos, _ ->
            if (!selecting) {
                selecting = true
                selected.add(shown[pos].path)
                showList(title, tabs, src)
            }
            true
        }

        val bp = LinearLayout.LayoutParams(WRAP, WRAP)
        bp.leftMargin = dp(8)
        barView.addView(chip("All", false) {
            selected.clear()
            for (x in shown) selected.add(x.path)
            refresh()
        }, bp)
        barView.addView(chip("Share", false) {
            val items = source().filter { selected.contains(it.path) }
            if (items.isEmpty()) toast("Select files first") else share(items)
        }, bp)
        barView.addView(chip("Delete", false, danger) {
            val items = source().filter { selected.contains(it.path) }
            if (items.isEmpty()) {
                toast("Select files first")
            } else {
                confirmDelete(items) {
                    selecting = false
                    selected.clear()
                    showList(title, tabs, src)
                }
            }
        }, bp)

        refresh()
        setScreen(root)
    }

    private fun showHidden() {
        val sv = ScrollView(this)
        val c = col()
        sv.addView(c)
        c.addView(header("Hidden and leftover"))
        val b = col()
        b.setPadding(dp(16), 0, dp(16), dp(16))
        c.addView(b)
        b.addView(tv("Files that may remain after deleting: trash, thumbnails, status media, cache and hidden folders.", 12f, sub))
        if (hidden.isEmpty()) {
            val e = tv("Nothing found yet. Rescan from Home.", 14f, tx)
            e.setPadding(0, dp(16), 0, 0)
            b.addView(e)
        }
        for ((name, items) in hidden.entries.sortedByDescending { it.value.sumOf { x -> x.size } }) {
            val bytes = items.sumOf { it.size }
            val r = cardBox()
            r.addView(tv(name, 15f, tx, true))
            r.addView(tv(items.size.toString() + " files | " + Formatter.formatFileSize(this, bytes), 12f, sub))
            r.setOnClickListener { openList(name, false) { hidden[name] ?: emptyList() } }
            b.addView(r, lpTop(8))
        }
        setScreen(sv)
    }

    private fun showFolders() {
        val sv = ScrollView(this)
        val c = col()
        c.setPadding(dp(16), dp(12), dp(16), dp(16))
        sv.addView(c)
        c.addView(tv("Folders", 20f, tx, true))
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val groups = allItems().groupBy { it.dir }.entries.sortedByDescending { it.value.size }.take(200)
        if (groups.isEmpty()) {
            val e = tv("No files yet. Scan from Home.", 14f, sub)
            e.setPadding(0, dp(16), 0, 0)
            c.addView(e)
        }
        for (g in groups) {
            val dir = g.key
            val r = cardBox()
            val nm = dir.removePrefix(rootPath).trimStart('/').ifEmpty { "Storage root" }
            val t = tv(nm, 14f, tx, true)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.START
            r.addView(t)
            r.addView(tv(g.value.size.toString() + " files | " + Formatter.formatFileSize(this, g.value.sumOf { it.size }), 12f, sub))
            r.setOnClickListener { openList(nm.substringAfterLast('/'), false) { allItems().filter { x -> x.dir == dir } } }
            c.addView(r, lpTop(8))
        }
        setScreen(sv)
    }

    private fun kv(label: String, v: TextView): View {
        val r = row()
        r.setPadding(0, dp(8), 0, dp(8))
        r.addView(tv(label, 13f, sub), LinearLayout.LayoutParams(dp(78), WRAP))
        v.textSize = 13f
        r.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
        return r
    }

    private fun dur(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val mi = (s % 3600) / 60
        val se = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, mi, se) else String.format("%d:%02d", mi, se)
    }

    private fun infoOf(m: M): String {
        return try {
            if (m.type == 0) {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(m.path, o)
                if (o.outWidth > 0) o.outWidth.toString() + " x " + o.outHeight else "Unknown"
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(m.path)
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val ds = if (d != null) dur(d) else "Unknown"
                    if (m.type == 1 && w != null && h != null) ds + " | " + w + " x " + h else ds
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun showDetail(m: M) {
        val sv = ScrollView(this)
        val c = col()
        sv.addView(c)
        c.addView(header("File details"))
        val body = col()
        body.setPadding(dp(16), dp(4), dp(16), dp(24))
        c.addView(body)

        val pv = FrameLayout(this)
        pv.background = rounded(tint(typeColor[m.type]), 12, 0)
        pv.clipToOutline = true
        pv.addView(tv(typeGlyph[m.type], 28f, typeColor[m.type], true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        pv.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        if (m.type == 1) {
            pv.addView(tv("▶", 40f, Color.WHITE, true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        loadThumb(iv, m, 1000)
        pv.setOnClickListener { openFile(m) }
        body.addView(pv, LinearLayout.LayoutParams(MATCH, dp(240)))

        val nm = tv(m.name, 16f, tx, true)
        nm.setPadding(0, dp(12), 0, dp(4))
        body.addView(nm)
        body.addView(kv("Location", tv(m.dir, 13f, tx)))
        body.addView(kv("Size", tv(Formatter.formatFileSize(this, m.size), 13f, tx)))
        body.addView(kv("Modified", tv(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.mod)), 13f, tx)))
        val info = tv("…", 13f, tx)
        body.addView(kv(if (m.type == 0) "Resolution" else "Info", info))
        Thread {
            val s = infoOf(m)
            runOnUiThread { info.text = s }
        }.start()

        val g1 = LinearLayout(this)
        g1.orientation = LinearLayout.HORIZONTAL
        g1.addView(btn("Open") { openFile(m) }, bl())
        g1.addView(btn("Share") { share(listOf(m)) }, bl())
        val g2 = LinearLayout(this)
        g2.orientation = LinearLayout.HORIZONTAL
        g2.addView(btn("Copy path") { copyPath(m) }, bl())
        g2.addView(btn("Save copy") { saveCopy(m) }, bl())
        body.addView(g1, lpTop(16))
        body.addView(g2, lpTop(8))
        body.addView(btn("Delete", danger, danger) { confirmDelete(listOf(m)) { goBack() } }, lpTop(8))
        setScreen(sv)
    }

    private fun mime(m: M): String {
        val ext = m.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (m.type) {
            0 -> "image/*"
            1 -> "video/*"
            else -> "audio/*"
        }
    }

    private fun uriOf(m: M): Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", File(m.path))

    private fun openFile(m: M) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uriOf(m), mime(m))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("No app found to open this file")
        }
    }

    private fun share(items: List<M>) {
        try {
            val i: Intent
            if (items.size == 1) {
                i = Intent(Intent.ACTION_SEND)
                i.type = mime(items[0])
                i.putExtra(Intent.EXTRA_STREAM, uriOf(items[0]))
            } else {
                i = Intent(Intent.ACTION_SEND_MULTIPLE)
                i.type = "*/*"
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { uriOf(it) }))
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Exception) {
            toast("Can't share this file")
        }
    }

    private fun copyPath(m: M) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", m.path))
        toast("Path copied")
    }

    private fun saveCopy(m: M) {
        Thread {
            val ok = try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaScanner Saved")
                dir.mkdirs()
                val base = m.name.substringBeforeLast('.')
                val ext = m.name.substringAfterLast('.', "")
                var d = File(dir, m.name)
                var n = 1
                while (d.exists()) {
                    d = File(dir, base + "_" + n + (if (ext.isEmpty()) "" else "." + ext))
                    n++
                }
                File(m.path).copyTo(d)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread { toast(if (ok) "Saved to Download/MediaScanner Saved" else "Couldn't save copy") }
        }.start()
    }

    private fun confirmDelete(items: List<M>, onDone: () -> Unit) {
        val run = {
            doDelete(items)
            onDone()
        }
        if (prefs.getBoolean("ask", true)) {
            AlertDialog.Builder(this)
                .setTitle("Delete " + items.size + (if (items.size == 1) " file?" else " files?"))
                .setMessage(if (items.size == 1) items[0].path else "This can't be undone.")
                .setPositiveButton("Delete") { _, _ -> run() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            run()
        }
    }

    private fun doDelete(items: List<M>) {
        val gone = HashSet<String>()
        for (m in items) {
            if (File(m.path).delete()) gone.add(m.path)
        }
        for (l in lists) l.removeAll { gone.contains(it.path) }
        for (l in hidden.values) l.removeAll { gone.contains(it.path) }
        if (gone.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, gone.toTypedArray(), null, null)
        }
        toast("Deleted " + gone.size + (if (gone.size == 1) " file" else " files"))
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Couldn't open link")
        }
    }

    private fun linkRow(glyph: String, color: Int, title: String, handle: String, url: String): View {
        val r = row()
        r.background = rounded(card, 12, 0)
        r.setPadding(dp(12), dp(12), dp(12), dp(12))
        val g = tv(glyph, 13f, color, true)
        g.gravity = Gravity.CENTER
        g.background = rounded(tint(color), 8, 0)
        r.addView(g, LinearLayout.LayoutParams(dp(38), dp(38)))
        val t = col()
        t.setPadding(dp(12), 0, 0, 0)
        t.addView(tv(title, 14f, tx, true))
        t.addView(tv(handle, 12f, sub))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(tv("Open", 13f, accent, true))
        r.setOnClickListener { openLink(url) }
        r.layoutParams = lpTop(8)
        return r
    }

    private fun switchRow(title: String, s: String, key: String, def: Boolean): View {
        val r = row()
        r.background = rounded(card, 12, 0)
        r.setPadding(dp(14), dp(12), dp(14), dp(12))
        val t = col()
        t.addView(tv(title, 14f, tx, true))
        t.addView(tv(s, 12f, sub))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        r.addView(sw)
        r.layoutParams = lpTop(8)
        return r
    }

    private fun showSettings() {
        val sv = ScrollView(this)
        val c = col()
        c.setPadding(dp(16), dp(12), dp(16), dp(24))
        sv.addView(c)
        c.addView(tv("Settings", 20f, tx, true))

        c.addView(label("Theme"))
        val tr = row()
        val cur = prefs.getString("theme", "System")
        for (t in arrayOf("System", "Light", "Dark")) {
            val p = LinearLayout.LayoutParams(WRAP, WRAP)
            p.rightMargin = dp(8)
            tr.addView(chip(t, cur == t) {
                prefs.edit().putString("theme", t).putInt("startTab", 2).apply()
                recreate()
            }, p)
        }
        c.addView(tr)

        c.addView(label("Contact us"))
        c.addView(linkRow("T", 0xFF2AABEE.toInt(), "Telegram support", "@blvcknetwork", "https://t.me/blvcknetwork"))
        c.addView(linkRow("IG", 0xFFD4537E.toInt(), "Instagram", "@tonmoyhazar", "https://instagram.com/tonmoyhazar"))

        c.addView(label("General"))
        c.addView(switchRow("Confirm before delete", "Ask every time before deleting files", "ask", true))
        c.addView(switchRow("Scan hidden folders", "Applies on the next scan", "scanHidden", true))

        val ar = row()
        ar.background = rounded(card, 12, 0)
        ar.setPadding(dp(14), dp(12), dp(14), dp(12))
        val at = col()
        at.addView(tv("All files access", 14f, tx, true))
        at.addView(tv("Needed to scan the full storage", 12f, sub))
        ar.addView(at, LinearLayout.LayoutParams(0, WRAP, 1f))
        val ok = hasAccess()
        ar.addView(tv(if (ok) "Allowed" else "Allow", 13f, if (ok) 0xFF1D9E75.toInt() else accent, true))
        ar.setOnClickListener { requestAccess() }
        c.addView(ar, lpTop(8))

        val ab = row()
        ab.setPadding(0, dp(20), 0, 0)
        val ic = ImageView(this)
        ic.setImageResource(R.mipmap.ic_launcher)
        ab.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
        val abt = col()
        abt.setPadding(dp(12), 0, 0, 0)
        abt.addView(tv("Media Scanner", 14f, tx, true))
        abt.addView(tv("Version 1.0. Works fully offline.", 12f, sub))
        ab.addView(abt)
        c.addView(ab)
        setScreen(sv)
    }
}
