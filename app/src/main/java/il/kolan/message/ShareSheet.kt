package il.kolan.message

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import il.kolan.R
import java.io.File

/** Result of trying to hand a file to WhatsApp. */
enum class ShareOutcome {
    SHARED_TO_WHATSAPP,
    SHARED_TO_CHOOSER,
    FAILED,
}

/**
 * Hands the exported message to WhatsApp, or to the system chooser when it is not installed.
 *
 * The file goes out as a content URI from our FileProvider, never as a file path: that is what
 * lets WhatsApp read it without any storage permission on either side.
 */
object ShareSheet {

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun share(context: Context, file: File, mimeType: String): ShareOutcome {
        if (!file.exists() || file.length() == 0L) return ShareOutcome.FAILED

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return ShareOutcome.FAILED

        val installedWhatsApp = WHATSAPP_PACKAGES.firstOrNull { isInstalled(context, it) }

        if (installedWhatsApp != null) {
            val direct = buildSendIntent(uri, mimeType).setPackage(installedWhatsApp)
            if (direct.resolveActivity(context.packageManager) != null) {
                context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ShareOutcome.SHARED_TO_WHATSAPP
            }
        }

        val chooser = Intent.createChooser(
            buildSendIntent(uri, mimeType),
            context.getString(R.string.message_share_other),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (chooser.resolveActivity(context.packageManager) != null) {
            context.startActivity(chooser)
            ShareOutcome.SHARED_TO_CHOOSER
        } else {
            ShareOutcome.FAILED
        }
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(intent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (chooser.resolveActivity(context.packageManager) != null) {
            context.startActivity(chooser)
        }
    }

    fun isWhatsAppInstalled(context: Context): Boolean =
        WHATSAPP_PACKAGES.any { isInstalled(context, it) }

    private fun buildSendIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
