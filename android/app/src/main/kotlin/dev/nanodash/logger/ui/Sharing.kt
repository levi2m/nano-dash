package dev.nanodash.logger.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.core.content.FileProvider

/** Hands the GPX to whichever installed app opens routes (OsmAnd, Organic Maps, ...), so the app itself needs no network. */
internal fun openRoute(context: Context, gpx: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", gpx)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml")
        .apply { clipData = ClipData.newRawUri("", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(view, "Open route in").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun share(context: Context, files: List<java.io.File>) {
    if (files.isEmpty()) return
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share track").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
