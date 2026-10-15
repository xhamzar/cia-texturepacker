package com.cia.texturepacker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dark code viewer / editor with format tabs (JSON | XML | plist | TXT). */
class DataEditorActivity : AppCompatActivity() {

    private lateinit var tvCode: TextView
    private lateinit var etCode: EditText
    private lateinit var toolbar: MaterialToolbar

    private var currentFormat = "JSON"
    private var editMode = false
    private val tabButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_editor)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { onMenu(it) }

        tvCode = findViewById(R.id.tvCode)
        etCode = findViewById(R.id.etCode)

        tabButtons.addAll(
            listOf(
                findViewById(R.id.tabJson),
                findViewById(R.id.tabXml),
                findViewById(R.id.tabPlist),
                findViewById(R.id.tabTxt)
            )
        )
        val keys = listOf("JSON", "XML", "plist", "TXT")
        tabButtons.forEachIndexed { i, b ->
            b.setOnClickListener { switchFormat(keys[i]) }
        }

        if (DataHolder.formats.isEmpty()) {
            Toast.makeText(this, R.string.pack_first, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentFormat = DataHolder.format.ifEmpty { "JSON" }
        refreshTabs()
        showCode()
    }

    private fun currentTriple(): Triple<String, String, String> =
        DataHolder.formats[currentFormat] ?: DataHolder.formats.values.first()

    private fun currentFileName(): String {
        val ( _, ext, _) = currentTriple()
        return "${DataHolder.baseName}.$ext"
    }

    private fun refreshTabs() {
        val keys = listOf("JSON", "XML", "plist", "TXT")
        tabButtons.forEachIndexed { i, b ->
            val active = keys[i] == currentFormat
            b.setBackgroundColor(
                if (active) ContextCompat.getColor(this, R.color.blue)
                else ContextCompat.getColor(this, android.R.color.transparent)
            )
            b.setTextColor(
                if (active) ContextCompat.getColor(this, android.R.color.white)
                else ContextCompat.getColor(this, R.color.code_text)
            )
        }
    }

    private fun switchFormat(fmt: String) {
        // stash edits from edit mode before switching
        if (editMode) {
            val edited = etCode.text.toString()
            val (_, ext, mime) = currentTriple()
            DataHolder.formats = DataHolder.formats.toMutableMap().apply {
                put(currentFormat, Triple(edited, ext, mime))
            }
        }
        currentFormat = fmt
        DataHolder.format = fmt
        editMode = false
        etCode.visibility = View.GONE
        refreshTabs()
        showCode()
    }

    private fun showCode() {
        val (text, _, _) = currentTriple()
        toolbar.title = currentFileName()
        tvCode.text = CodeHighlighter.highlight(text, currentFormat)
    }

    private fun onMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_copy -> copyToClipboard()
            R.id.action_edit -> toggleEdit()
            R.id.action_save -> saveData()
            R.id.action_share -> shareData()
        }
        return true
    }

    private fun toggleEdit() {
        editMode = !editMode
        if (editMode) {
            etCode.setText(currentTriple().first)
            etCode.visibility = View.VISIBLE
            toolbar.menu.findItem(R.id.action_edit)?.title = getString(R.string.view)
            Toast.makeText(this, R.string.edit, Toast.LENGTH_SHORT).show()
        } else {
            // apply edits back to holder
            val edited = etCode.text.toString()
            val (_, ext, mime) = currentTriple()
            DataHolder.formats = DataHolder.formats.toMutableMap().apply {
                put(currentFormat, Triple(edited, ext, mime))
            }
            etCode.visibility = View.GONE
            toolbar.menu.findItem(R.id.action_edit)?.title = getString(R.string.edit)
            showCode()
        }
    }

    private fun copyToClipboard() {
        val text = if (editMode) etCode.text.toString() else currentTriple().first
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("atlas-data", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun saveData() {
        val text = if (editMode) etCode.text.toString() else currentTriple().first
        val (_, _, mime) = currentTriple()
        val name = currentFileName()
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = saveToDownloads(name, mime, text.toByteArray(Charsets.UTF_8))
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@DataEditorActivity,
                    if (uri != null) getString(R.string.saved) else getString(R.string.save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shareData() {
        val text = if (editMode) etCode.text.toString() else currentTriple().first
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentFileName())
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share)))
    }

    private fun saveToDownloads(fileName: String, mime: String, bytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CiaTexturePacker")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "CiaTexturePacker"
            )
            dir.mkdirs()
            val f = File(dir, fileName)
            f.writeBytes(bytes)
            Uri.fromFile(f)
        }
    }
}
