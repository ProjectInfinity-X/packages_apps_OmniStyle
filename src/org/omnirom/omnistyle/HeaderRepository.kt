/*
 * Copyright (C) 2026 crDroid Android Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.omnirom.omnistyle

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/** A header pack: one package, optionally with a named config (DaylightHeaderPack1). */
data class HeaderPack(
    val label: String,
    val packageName: String,
    /** Full activity / config name; HeaderUtils uses the part after the last '.' + ".xml". */
    val configName: String?,
) {
    val key: String get() = if (configName == null) packageName else "$packageName/$configName"
}

data class HeaderEntry(
    val image: String,
    val name: String?,
    /** 0 = day header, 1 = hour header, 2 = list/random header (see HeaderUtils). */
    val type: Int,
    val hour: Int,
    val day: Int,
    val month: Int,
) {
    val displayName: String get() = name ?: image
}

class LoadedPack(
    val pack: HeaderPack,
    val resources: Resources?,
    val creator: String?,
    val headers: List<HeaderEntry>,
)

/** The header currently applied by the "static" provider, as "package/image". */
data class AppliedHeader(val packageName: String, val image: String) {
    val key: String get() = "$packageName/$image"
}

object HeaderRepository {
    private const val TAG = "OmniStyleHeaders"

    private const val ACTION_HEADER_PACK = "org.omnirom.DaylightHeaderPack"
    private const val ACTION_HEADER_PACK_1 = "org.omnirom.DaylightHeaderPack1"

    private const val STATUS_BAR_CUSTOM_HEADER_IMAGE = "status_bar_custom_header_image"
    private const val STATUS_BAR_CUSTOM_HEADER_PROVIDER = "status_bar_custom_header_provider"
    private const val PROVIDER_STATIC = "static"

    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun queryPacks(context: Context): List<HeaderPack> {
        val pm = context.packageManager
        // Keyed by label, like the original implementation, so duplicates collapse.
        val byLabel = LinkedHashMap<String, HeaderPack>()
        pm.queryIntentActivities(Intent(ACTION_HEADER_PACK), 0).forEach { r ->
            val label = r.activityInfo.loadLabel(pm)?.toString() ?: r.activityInfo.packageName
            byLabel[label] = HeaderPack(label, r.activityInfo.packageName, null)
        }
        pm.queryIntentActivities(Intent(ACTION_HEADER_PACK_1), 0).forEach { r ->
            val label = r.activityInfo.loadLabel(pm)?.toString() ?: r.activityInfo.packageName
            byLabel[label] = HeaderPack(label, r.activityInfo.packageName, r.activityInfo.name)
        }
        return byLabel.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun loadPack(context: Context, pack: HeaderPack): LoadedPack = try {
        val res = context.packageManager.getResourcesForApplication(pack.packageName)
        val infos = ArrayList<HeaderUtils.DaylightHeaderInfo>()
        val creator = HeaderUtils.loadHeaders(res, pack.configName, infos)
        LoadedPack(
            pack = pack,
            resources = res,
            creator = creator?.takeIf { it.isNotBlank() },
            headers = infos.map {
                HeaderEntry(it.mImage, it.mName, it.mType, it.mHour, it.mDay, it.mMonth)
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load header pack ${pack.key}", e)
        LoadedPack(pack, null, null, emptyList())
    }

    fun resourcesFor(context: Context, packageName: String): Resources? = try {
        context.packageManager.getResourcesForApplication(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Decodes a header drawable, downsampled to roughly [reqWidth] pixels wide. Call off the main thread. */
    fun loadThumbnail(res: Resources, packageName: String, image: String, reqWidth: Int): Bitmap? {
        val cacheKey = "$packageName/$image@$reqWidth"
        thumbCache.get(cacheKey)?.let { return it }

        val resId = res.getIdentifier(image, "drawable", packageName)
        if (resId == 0) return null

        val bitmap = try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
            BitmapFactory.decodeResource(res, resId, bounds)
            if (bounds.outWidth > 0) {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
                BitmapFactory.decodeResource(res, resId, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inScaled = false
                })
            } else {
                // Not a bitmap resource (e.g. a vector or XML drawable).
                val d = res.getDrawable(resId, null)
                val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else reqWidth
                val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else reqWidth / 3
                d.toBitmap(w, h)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode $packageName/$image", e)
            null
        }
        bitmap?.let { thumbCache.put(cacheKey, it) }
        return bitmap
    }

    fun appliedHeader(context: Context): AppliedHeader? {
        val cr = context.contentResolver
        if (Settings.System.getString(cr, STATUS_BAR_CUSTOM_HEADER_PROVIDER) != PROVIDER_STATIC) {
            return null
        }
        val value = Settings.System.getString(cr, STATUS_BAR_CUSTOM_HEADER_IMAGE) ?: return null
        val idx = value.indexOf('/')
        if (idx <= 0 || idx == value.length - 1) return null
        return AppliedHeader(value.substring(0, idx), value.substring(idx + 1))
    }

    fun applyHeader(context: Context, packageName: String, image: String) {
        val cr = context.contentResolver
        Settings.System.putString(cr, STATUS_BAR_CUSTOM_HEADER_IMAGE, "$packageName/$image")
        Settings.System.putString(cr, STATUS_BAR_CUSTOM_HEADER_PROVIDER, PROVIDER_STATIC)
    }
}
