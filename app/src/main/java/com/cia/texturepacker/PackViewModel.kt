package com.cia.texturepacker

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One imported image. */
data class SpriteItem(
    val name: String,
    val bitmap: Bitmap,
    val thumb: Bitmap,
    val width: Int,
    val height: Int
)

/** Pack settings, edited in SettingsFragment. */
data class PackSettings(
    var maxWidth: Int = 1024,
    var maxHeight: Int = 1024,
    var padding: Int = 2,
    var spacing: Int = 2,
    var trim: Boolean = true,
    var allowRotation: Boolean = false,
    var imageFormat: String = "PNG",   // PNG | WebP
    var filter: String = "Nearest",    // Nearest | Linear
    var background: String = "Transparan", // Transparan | Putih | Hitam
    var packMode: String = "Otomatis", // Otomatis | Grid
    var cellWidth: Int = 64,
    var cellHeight: Int = 64
)

/** Shared state for all fragments. */
class PackViewModel(app: Application) : AndroidViewModel(app) {

    private val _sprites = MutableLiveData<List<SpriteItem>>(emptyList())
    val sprites: LiveData<List<SpriteItem>> = _sprites

    private val _packResult = MutableLiveData<PackResult?>()
    val packResult: LiveData<PackResult?> = _packResult

    private val _status = MutableLiveData<String>()
    val status: LiveData<String> = _status

    val settings = PackSettings()

    fun spriteCount(): Int = _sprites.value?.size ?: 0

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _status.value = getApplication<Application>().getString(R.string.loading_images, uris.size)
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = mutableListOf<SpriteItem>()
            val cr = getApplication<Application>().contentResolver
            for (uri in uris) {
                try {
                    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { }
                val bmp = try {
                    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) { null } ?: continue
                val name = displayName(cr, uri)
                val tw = 160
                val th = (tw * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
                val thumb = Bitmap.createScaledBitmap(bmp, tw, th, true)
                loaded.add(SpriteItem(name, bmp, thumb, bmp.width, bmp.height))
            }
            withContext(Dispatchers.Main) {
                if (loaded.isEmpty()) {
                    _status.value = getApplication<Application>().getString(R.string.no_images_loaded)
                } else {
                    _sprites.value = (_sprites.value ?: emptyList()) + loaded
                    _packResult.value = null // invalidate previous pack
                    _status.value = getApplication<Application>().getString(R.string.images_loaded, loaded.size)
                }
            }
        }
    }

    /**
     * Import Atlas: load an atlas image + its data file (plist/json/xml/txt).
     * Sprites are cropped exactly per the data file's rects and keep the
     * data file's names — accurate, matching the game code (no guessing).
     */
    fun importAtlas(imageUri: Uri, dataUri: Uri) {
        val app = getApplication<Application>()
        _status.value = app.getString(R.string.importing_atlas)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = app.contentResolver
                try {
                    cr.takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { }
                try {
                    cr.takePersistableUriPermission(dataUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { }
                val atlas = cr.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalArgumentException(app.getString(R.string.import_bad_image))
                val dataName = displayName(cr, dataUri)
                val text = cr.openInputStream(dataUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalArgumentException(app.getString(R.string.import_bad_data))
                // Guard: plist knows its atlas pixel size — the image must match,
                // otherwise every sprite comes out cut/misplaced (wrong pair).
                if (dataName.lowercase().endsWith(".plist")) {
                    AtlasImporter.parsePlistSize(text)?.let { (ew, eh) ->
                        if (ew != atlas.width || eh != atlas.height) {
                            throw IllegalArgumentException(
                                app.getString(R.string.import_size_mismatch, ew, eh, atlas.width, atlas.height)
                            )
                        }
                    }
                }
                val frames = AtlasImporter.parse(text, dataName)
                if (frames.isEmpty()) throw IllegalArgumentException(app.getString(R.string.import_no_frames))
                val loaded = mutableListOf<SpriteItem>()
                var skipped = 0
                for (f in frames) {
                    try {
                        val bmp = cropFrame(atlas, f)
                        loaded.add(SpriteItem(f.name, bmp, makeThumb(bmp), bmp.width, bmp.height))
                    } catch (_: Exception) { skipped++ }
                }
                withContext(Dispatchers.Main) {
                    if (loaded.isEmpty()) {
                        _status.value = app.getString(R.string.import_no_frames)
                    } else {
                        _sprites.value = (_sprites.value ?: emptyList()) + loaded
                        _packResult.value = null // invalidate previous pack
                        _status.value = app.getString(R.string.import_done, loaded.size, dataName) +
                            if (skipped > 0) app.getString(R.string.import_skipped, skipped) else ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _status.value = app.getString(R.string.import_failed, e.message ?: "?")
                }
            }
        }
    }

    /**
     * Crop one frame from the atlas.
     *
     * Rotated sprites are stored turned 90° CW in the atlas while the rect
     * carries logical dims (CodeAndWeb convention): crop the swapped rect,
     * then turn it back. If the rect already carries stored (swapped) dims,
     * crop it verbatim instead.
     */
    private fun cropFrame(atlas: Bitmap, f: ImportedFrame): Bitmap {
        val (cw, ch) = if (f.rotated) {
            val alreadySwapped = f.w == f.trimH && f.h == f.trimW && f.w != f.h
            if (alreadySwapped) f.w to f.h else f.h to f.w
        } else {
            f.w to f.h
        }
        val x = f.x.coerceIn(0, atlas.width - 1)
        val y = f.y.coerceIn(0, atlas.height - 1)
        val w = cw.coerceIn(1, atlas.width - x)
        val h = ch.coerceIn(1, atlas.height - y)
        val bmp = Bitmap.createBitmap(atlas, x, y, w, h)
        if (!f.rotated) return bmp
        // Undo the 90° CW pack rotation.
        val m = Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun makeThumb(bmp: Bitmap): Bitmap {
        val tw = 160
        val th = (tw * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, tw, th, true)
    }

    /** Rename a sprite. Duplicate names get an automatic " (2)" suffix. */
    fun rename(pos: Int, newName: String) {
        val cur = _sprites.value?.toMutableList() ?: return
        if (pos !in cur.indices) return
        val clean = newName.trim().replace(Regex("[/\\\\]"), "_")
        if (clean.isEmpty() || clean == cur[pos].name) return
        val taken = cur.mapIndexedNotNull { i, s -> if (i != pos) s.name else null }.toSet()
        var final = clean
        var i = 2
        while (final in taken) {
            final = "$clean ($i)"
            i++
        }
        cur[pos] = cur[pos].copy(name = final)
        _sprites.value = cur
        _packResult.value = null // invalidate previous pack
        _status.value = getApplication<Application>().getString(R.string.renamed, final)
    }

    fun removeAt(pos: Int) {
        val cur = _sprites.value?.toMutableList() ?: return
        if (pos in cur.indices) {
            cur.removeAt(pos)
            _sprites.value = cur
            _packResult.value = null
        }
    }

    fun clearAll() {
        _sprites.value = emptyList()
        _packResult.value = null
    }

    /** Replace a sprite's bitmap after pixel editing. Regenerates the thumbnail. */
    fun replaceBitmap(pos: Int, newBitmap: Bitmap) {
        val cur = _sprites.value?.toMutableList() ?: return
        if (pos !in cur.indices) return
        val old = cur[pos]
        val tw = 160
        val th = (tw * newBitmap.height / newBitmap.width.toFloat()).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(newBitmap, tw, th, true)
        // NOTE: do NOT recycle old bitmaps here — the RecyclerView may still be
        // drawing them; recycling causes "Canvas: trying to use a recycled bitmap" crash.
        // Let the GC reclaim them once the adapter drops its references.
        cur[pos] = old.copy(bitmap = newBitmap, thumb = thumb, width = newBitmap.width, height = newBitmap.height)
        _sprites.value = cur
        _packResult.value = null // invalidate previous pack
        _status.value = getApplication<Application>().getString(R.string.pixel_saved)
    }

    /** Remove multiple sprites by adapter positions (batch delete). */
    fun removePositions(positions: Collection<Int>) {        val cur = _sprites.value?.toMutableList() ?: return
        val sorted = positions.filter { it in cur.indices }.sortedDescending()
        if (sorted.isEmpty()) return
        for (p in sorted) cur.removeAt(p)
        _sprites.value = cur
        _packResult.value = null
        _status.value = getApplication<Application>().getString(R.string.deleted_n, sorted.size)
    }

    /**
     * Auto-Pisah: detect connected opaque regions in every image and
     * split multi-sprite images (e.g. Tower1.png 128x256 with 2 towers
     * stacked) into separate sprites. Replaces the image list.
     */
    fun autoSplit() {
        val items = _sprites.value ?: emptyList()
        if (items.isEmpty()) {
            _status.value = getApplication<Application>().getString(R.string.need_images)
            return
        }
        _status.value = getApplication<Application>().getString(R.string.splitting)
        viewModelScope.launch(Dispatchers.Default) {
            val out = mutableListOf<SpriteItem>()
            var splitImages = 0
            for (item in items) {
                val parts = try {
                    SpriteSplitter.split(item.bitmap, item.name)
                } catch (e: Exception) {
                    e.printStackTrace()
                    listOf(item.name to item.bitmap)
                }
                if (parts.size > 1) splitImages++
                for ((name, bmp) in parts) {
                    if (bmp === item.bitmap) {
                        out.add(item)
                    } else {
                        val tw = 160
                        val th = (tw * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
                        val thumb = Bitmap.createScaledBitmap(bmp, tw, th, true)
                        out.add(SpriteItem(name, bmp, thumb, bmp.width, bmp.height))
                    }
                }
            }
            withContext(Dispatchers.Main) {
                _sprites.value = out
                _packResult.value = null // invalidate previous pack
                val app = getApplication<Application>()
                _status.value = if (splitImages == 0) {
                    app.getString(R.string.split_none)
                } else {
                    app.getString(R.string.split_done, out.size)
                }
            }
        }
    }

    /**
     * Auto-Transparan: detect the solid background color of every image
     * (from the corner pixels) and make matching pixels transparent.
     * Replaces the image list with the processed bitmaps.
     */
    fun autoRemoveBackground() {
        val items = _sprites.value ?: emptyList()
        if (items.isEmpty()) {
            _status.value = getApplication<Application>().getString(R.string.need_images)
            return
        }
        _status.value = getApplication<Application>().getString(R.string.removing_bg)
        viewModelScope.launch(Dispatchers.Default) {
            val out = mutableListOf<SpriteItem>()
            for (item in items) {
                val bmp = try {
                    BackgroundRemover.removeBackground(item.bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                    item.bitmap
                }
                if (bmp === item.bitmap) {
                    out.add(item)
                } else {
                    val tw = 160
                    val th = (tw * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
                    val thumb = Bitmap.createScaledBitmap(bmp, tw, th, true)
                    out.add(SpriteItem(item.name, bmp, thumb, bmp.width, bmp.height))
                }
            }
            withContext(Dispatchers.Main) {
                _sprites.value = out
                _packResult.value = null // invalidate previous pack
                _status.value = getApplication<Application>().getString(R.string.bg_removed)
            }
        }
    }

    fun pack(onDone: ((Boolean) -> Unit)? = null) {
        val items = _sprites.value ?: emptyList()
        if (items.isEmpty()) {
            _status.value = getApplication<Application>().getString(R.string.need_images)
            onDone?.invoke(false)
            return
        }
        _status.value = getApplication<Application>().getString(R.string.packing)
        val s = settings
        val inputs = items.map { SpriteInput(it.name, it.bitmap) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                if (s.packMode == "Grid") {
                    SpritePacker.packGrid(inputs, s.maxWidth, s.maxHeight, s.padding, s.spacing, s.trim, s.cellWidth, s.cellHeight)
                } else {
                    SpritePacker.pack(inputs, s.maxWidth, s.maxHeight, s.padding, s.spacing, s.trim, s.allowRotation)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            withContext(Dispatchers.Main) {
                if (result == null) {
                    _packResult.value = null
                    _status.value = getApplication<Application>().getString(R.string.pack_failed, s.maxWidth, s.maxHeight)
                    onDone?.invoke(false)
                } else {
                    _packResult.value = result
                    _status.value = if (result.scaledDown) {
                        getApplication<Application>().getString(
                            R.string.pack_success_scaled, s.cellWidth, s.cellHeight
                        )
                    } else {
                        getApplication<Application>().getString(R.string.pack_success)
                    }
                    onDone?.invoke(true)
                }
            }
        }
    }

    private fun displayName(cr: android.content.ContentResolver, uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "sprite.png"
        var c: Cursor? = null
        try {
            c = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null && c.moveToFirst()) c.getString(0)?.let { name = it }
        } catch (_: Exception) { } finally { c?.close() }
        if (!name.contains('.')) name += ".png"
        return name
    }
}
