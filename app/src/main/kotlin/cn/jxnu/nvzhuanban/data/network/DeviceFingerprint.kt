package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * Generates a stable CAS visitor id without reading hardware identifiers.
 *
 * The CAS endpoint only needs a browser-like opaque value. A per-install UUID
 * is enough here, and hashing keeps the wire value fixed-width and non-raw.
 */
object DeviceFingerprint {

    /** Returns a stable 32-character hex visitor id for this app installation. */
    fun visitorId(@Suppress("UNUSED_PARAMETER") context: Context, storage: AuthStorage): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(storage.installUuid().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Used only when AuthStorage creates the installation UUID for the first time. */
    internal fun newInstallUuid(): String = UUID.randomUUID().toString()
}
