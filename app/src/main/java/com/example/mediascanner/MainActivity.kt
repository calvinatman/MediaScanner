package com.example.mediascanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private val img = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val vid = setOf("mp4", "mkv", "3gp", "avi", "mov", "webm")
    private val aud = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "amr", "flac")

    private val all = mutableListOf<File>()
    private val shown = mutableListOf<File>()
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var filter = ""
    private var scanning = false
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 48, 16, 16)
        }
        status = TextView(this).apply { textSize = 16f; setPadding(8, 8, 8, 8) }
        root.addView(status)

        val bar = LinearLayout(this)
        val tabs = listOf("All" to "", "Photos" to "i", "Videos" to "v", "Audio" to "a")
        for ((label, key) in tabs) {
            val b = Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { filter = key; applyFilter() }
            }
            bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rescan = Button(this).apply {
            text = "Rescan"
            textSize = 11f
            setOnClickListener { scan() }
        }
        bar.addView(rescan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val f = shown[pos]
            AlertDialog.Builder(this)
                .setTitle("Delete file?")
                .setMessage(f.absolutePath)
                .setPositiveButton("Delete") { _, _ ->
                    if (f.delete()) {
                        all.remove(f)
                        applyFilter()
                    } else {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        status.text = "Long-press a file to delete it"
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (all.isEmpty() && !scanning) scan()
        } else if (!asked) {
            asked = true
            requestAccess()
        } else {
            status.text = "Permission needed. Allow file access and reopen the app."
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasAccess()) scan()
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    private fun typeOf(f: File): String {
        val e = f.extension.lowercase()
        return when {
            e in img -> "i"
            e in vid -> "v"
            e in aud -> "a"
            else -> ""
        }
    }

    private fun scan() {
        if (scanning || !hasAccess()) return
        scanning = true
        status.text = "Scanning..."
        Thread {
            val found = mutableListOf<File>()
            Environment.getExternalStorageDirectory().walkTopDown()
                .onFail { _, _ -> }
                .forEach { if (it.isFile && typeOf(it) != "") found.add(it) }
            runOnUiThread {
                all.clear()
                all.addAll(found.sortedByDescending { it.lastModified() })
                scanning = false
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        shown.clear()
        shown.addAll(all.filter { filter == "" || typeOf(it) == filter })
        val df = DateFormat.getDateTimeInstance()
        adapter.clear()
        adapter.addAll(shown.map {
            it.absolutePath + "\n" +
                Formatter.formatShortFileSize(this, it.length()) + "  |  " +
                df.format(Date(it.lastModified()))
        })
        status.text = "${shown.size} files found (long-press to delete)"
    }
}
