package app.proview.camera.photos

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** One photo taken with Proview, with the settings it was shot at. */
data class PhotoRecord(
    val uri: Uri,
    val takenAt: Long,
    val frame: Int,
    val iso: Int,
    val exposureNs: Long,
    val aperture: String,
    val whiteBalance: String,
    val favourite: Boolean = false,
)

/**
 * The app's own photo list. Files live in MediaStore under Pictures/Proview; this keeps the
 * shot settings alongside, so Library and Photo detail don't need to parse EXIF.
 */
class PhotoStore(context: Context) {
    private val prefs = context.getSharedPreferences("photos", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    fun all(): List<PhotoRecord> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            .filter { exists(it.uri) }
            .sortedByDescending { it.takenAt }
    }

    fun nextFrame(): Int = prefs.getInt(KEY_FRAME, 0) + 1

    fun add(record: PhotoRecord) {
        val list = all().toMutableList().apply { add(0, record) }
        save(list)
        prefs.edit().putInt(KEY_FRAME, record.frame).apply()
    }

    fun setFavourite(uri: Uri, favourite: Boolean) {
        save(all().map { if (it.uri == uri) it.copy(favourite = favourite) else it })
    }

    private fun exists(uri: Uri): Boolean =
        runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    private fun save(list: List<PhotoRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(r: PhotoRecord) = JSONObject()
        .put("uri", r.uri.toString())
        .put("takenAt", r.takenAt)
        .put("frame", r.frame)
        .put("iso", r.iso)
        .put("exposureNs", r.exposureNs)
        .put("aperture", r.aperture)
        .put("wb", r.whiteBalance)
        .put("fav", r.favourite)

    private fun fromJson(o: JSONObject) = PhotoRecord(
        uri = Uri.parse(o.getString("uri")),
        takenAt = o.getLong("takenAt"),
        frame = o.getInt("frame"),
        iso = o.getInt("iso"),
        exposureNs = o.getLong("exposureNs"),
        aperture = o.getString("aperture"),
        whiteBalance = o.getString("wb"),
        favourite = o.optBoolean("fav", false),
    )

    companion object {
        private const val KEY = "records"
        private const val KEY_FRAME = "lastFrame"
    }
}
