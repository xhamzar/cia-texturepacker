package com.cia.texturepacker

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private val vm: PackViewModel by viewModels()
    private lateinit var drawer: DrawerLayout
    private lateinit var pager: ViewPager2
    private lateinit var imagesFragment: ImagesFragment
    private lateinit var atlasFragment: AtlasFragment
    private lateinit var settingsFragment: SettingsFragment

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        drawer = findViewById(R.id.drawerLayout)
        val toggle = ActionBarDrawerToggle(
            this, drawer, toolbar,
            R.string.menu_about, R.string.menu_about
        )
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        val nav = findViewById<NavigationView>(R.id.navView)
        nav.setNavigationItemSelectedListener(this)

        imagesFragment = ImagesFragment().apply {
            onAddImages = { pickImages.launch(arrayOf("image/*")) }
            onMakeSheet = { makeSheet() }
        }
        atlasFragment = AtlasFragment().apply {
            onExport = { doExport() }
        }
        settingsFragment = SettingsFragment().apply {
            onGenerate = { makeSheet() }
        }

        pager = findViewById(R.id.viewPager)
        pager.adapter = PagerAdapter(this)
        pager.offscreenPageLimit = 2

        val tabs = findViewById<TabLayout>(R.id.tabLayout)
        val titles = arrayOf(
            getString(R.string.tab_images),
            getString(R.string.tab_atlas),
            getString(R.string.tab_settings)
        )
        TabLayoutMediator(tabs, pager) { tab, pos -> tab.text = titles[pos] }.attach()

        findViewById<android.view.View>(R.id.btnToolbarAdd).setOnClickListener {
            pickImages.launch(arrayOf("image/*"))
        }

        val btnToolbarSelect = findViewById<android.view.View>(R.id.btnToolbarSelect)
        btnToolbarSelect.setOnClickListener {
            if (pager.currentItem != 0) pager.setCurrentItem(0, true)
            // Post to ensure the fragment is attached/visible before entering mode.
            pager.post { imagesFragment.enterSelectionMode() }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                btnToolbarSelect.visibility =
                    if (position == 0 && vm.spriteCount() > 0) android.view.View.VISIBLE
                    else android.view.View.GONE
                if (position != 0) imagesFragment.exitSelectionMode()
            }
        })

        vm.status.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        vm.sprites.observe(this) { list ->
            if (pager.currentItem == 0) {
                btnToolbarSelect.visibility =
                    if (list.isNotEmpty()) android.view.View.VISIBLE
                    else android.view.View.GONE
            }
        }
    }

    private inner class PagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> imagesFragment
            1 -> atlasFragment
            else -> settingsFragment
        }
    }

    private fun makeSheet() {
        vm.pack { ok ->
            if (ok) pager.setCurrentItem(1, true)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_import -> pickImages.launch(arrayOf("image/*"))
            R.id.nav_atlas -> pager.setCurrentItem(1, true)
            R.id.nav_settings -> pager.setCurrentItem(2, true)
            R.id.nav_export -> doExport()
            R.id.nav_editor -> openDataEditor()
            R.id.nav_clear -> {
                vm.clearAll()
                Toast.makeText(this, R.string.clear, Toast.LENGTH_SHORT).show()
            }
            R.id.nav_about -> AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
        drawer.closeDrawers()
        return true
    }

    private fun stampBase(): String =
        "cia-atlas-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Export PNG/WebP + data file in the format chosen in the text editor (default JSON). */
    private fun doExport() {
        val result = vm.packResult.value
        if (result == null) {
            Toast.makeText(this, R.string.pack_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100
            )
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stamp = stampBase()
                val imgExt = if (vm.settings.imageFormat == "WebP") "webp" else "png"
                val pngName = "$stamp.$imgExt"
                // Data format comes from the editor's current tab (default JSON)
                val fmt = DataHolder.format.ifEmpty { "JSON" }
                val (dataText, dataExt, dataMime) = buildData(result, pngName, fmt)
                val dataName = "$stamp.$dataExt"

                val imgBytes = ByteArrayOutputStream().use { bos ->
                    val cf = if (imgExt == "webp") {
                        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                        else Bitmap.CompressFormat.WEBP
                    } else Bitmap.CompressFormat.PNG
                    result.atlas.compress(cf, 100, bos)
                    bos.toByteArray()
                }
                val dataBytes = dataText.toByteArray(Charsets.UTF_8)

                val imgUri = saveToDownloads(pngName, "image/$imgExt", imgBytes)
                val dataUri = saveToDownloads(dataName, dataMime, dataBytes)

                withContext(Dispatchers.Main) {
                    if (imgUri != null && dataUri != null) {
                        showShareDialog(imgUri, dataExt.uppercase())
                    } else {
                        Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "${getString(R.string.export_failed)} ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Build atlas data text in the given format label (JSON/XML/plist/TXT). */
    fun buildData(result: PackResult, imageName: String, format: String): Triple<String, String, String> {
        return when (format.uppercase()) {
            "XML" -> Triple(SpritePacker.toXml(result, imageName), "xml", "application/xml")
            "PLIST" -> Triple(SpritePacker.toPlist(result, imageName), "plist", "application/x-plist")
            "PLIST3" -> Triple(SpritePacker.toPlistV3(result, imageName), "plist", "application/x-plist")
            "TXT" -> Triple(SpritePacker.toTxt(result, imageName), "txt", "text/plain")
            else -> Triple(SpritePacker.toJson(result, imageName), "json", "application/json")
        }
    }

    private fun openDataEditor() {
        val result = vm.packResult.value
        if (result == null) {
            Toast.makeText(this, R.string.pack_first, Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = stampBase()
        val imgExt = if (vm.settings.imageFormat == "WebP") "webp" else "png"
        val imageName = "$stamp.$imgExt"
        DataHolder.baseName = stamp
        DataHolder.imageName = imageName
        DataHolder.formats = linkedMapOf(
            "JSON" to buildData(result, imageName, "JSON"),
            "XML" to buildData(result, imageName, "XML"),
            "plist" to buildData(result, imageName, "plist"),
            "PLIST3" to buildData(result, imageName, "PLIST3"),
            "TXT" to buildData(result, imageName, "TXT")
        )
        if (DataHolder.format.isEmpty()) DataHolder.format = "JSON"
        startActivity(Intent(this, DataEditorActivity::class.java))
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
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
            Uri.fromFile(f)
        }
    }

    private fun showShareDialog(imgUri: Uri, formatLabel: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setMessage(getString(R.string.export_msg, formatLabel))
            .setPositiveButton(R.string.share_btn) { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, imgUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.share)))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            doExport()
        } else if (requestCode == 100) {
            Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Holds generated atlas data for the editor (avoids huge intent extras). */
object DataHolder {
    var baseName: String = ""
    var imageName: String = ""
    var format: String = "JSON"
    var formats: Map<String, Triple<String, String, String>> = emptyMap()
}
