package com.plwd.audiochannelguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class AudioGuardApp : Application() {

    companion object {
        private const val PREFS_NAME = "audio_guard_prefs"
        private const val KEY_ENABLED = "guard_enabled"
        private const val KEY_ENHANCED_MODE = "enhanced_mode"
        private const val KEY_CLASSIC_BLUETOOTH_SOFT_GUARD = "classic_bluetooth_soft_guard"
        private const val KEY_CLASSIC_BLUETOOTH_WIDEBAND = "classic_bluetooth_wideband"
        private const val KEY_TILE_ADDED = "tile_added"
        private const val KEY_AUTO_START_CONFIRMED = "auto_start_confirmed"
        private const val KEY_BG_RESTRICT_CONFIRMED = "bg_restrict_confirmed"

        // Release keystore (plwd_cn.keystore) signing certificate SHA-256
        private const val EXPECTED_CERT_HASH =
            "222b4c298ca06cb38792288d3b5bfa5c77c00e423cc2ffc0b024b185e447fb52"

        // APK Signing Block magic and scheme IDs
        private val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        private const val APK_SIG_SCHEME_V2_ID = 0x7109871a
        private const val APK_SIG_SCHEME_V3_ID = 0x0f05368c
        private const val APK_SIG_SCHEME_V31_ID = 0x1b93ad61

        var isTampered = false
            private set

        fun getExpectedCertHash(): String = EXPECTED_CERT_HASH

        fun isGuardEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun setGuardEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
            AudioFixTile.requestTileRefresh(context)
        }

        fun setGuardEnabledSync(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).commit()
            AudioFixTile.requestTileRefresh(context)
        }

        fun isEnhancedModeEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENHANCED_MODE, false)
        }

        fun setEnhancedModeEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENHANCED_MODE, enabled).apply()
        }

        fun isClassicBluetoothWidebandEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_CLASSIC_BLUETOOTH_WIDEBAND, false)
        }

        fun isClassicBluetoothSoftGuardEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_CLASSIC_BLUETOOTH_SOFT_GUARD, false)
        }

        fun setClassicBluetoothSoftGuardEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_CLASSIC_BLUETOOTH_SOFT_GUARD, enabled).apply()
        }

        fun setClassicBluetoothWidebandEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_CLASSIC_BLUETOOTH_WIDEBAND, enabled).apply()
        }

        fun isTileAdded(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_TILE_ADDED, false)
        }

        fun setTileAdded(context: Context, added: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val wasAdded = prefs.getBoolean(KEY_TILE_ADDED, false)
            if (wasAdded == added) return

            prefs.edit().putBoolean(KEY_TILE_ADDED, added).apply()
            if (added) {
                AudioFixTile.requestTileRefresh(context)
            }
        }

        fun isAutoStartConfirmed(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START_CONFIRMED, false)
        }

        fun setAutoStartConfirmed(context: Context, confirmed: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_START_CONFIRMED, confirmed).apply()
        }

        fun isBgRestrictConfirmed(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_BG_RESTRICT_CONFIRMED, false)
        }

        fun setBgRestrictConfirmed(context: Context, confirmed: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_BG_RESTRICT_CONFIRMED, confirmed).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppUpdateManager.getInstance(this).handleAppLaunchCleanup()
        if (!verifySignature()) {
            isTampered = true
            Toast.makeText(this, "签名校验失败，应用可能被篡改", Toast.LENGTH_LONG).show()
        }
        if (isGuardEnabled(this)) {
            ServiceGuard.schedulePeriodicCheck(this)
        }
    }

    /**
     * Dual-layer signature verification.
     * Layer 1: PackageManager API (can be hooked by MT Manager's "元安装包" injection)
     * Layer 2: Direct APK file V2/V3 signing block parsing (bypasses Java API hooks)
     * Both layers must pass.
     */
    private fun verifySignature(): Boolean {
        return verifyViaPackageManager() && verifyViaApkFile()
    }

    private fun verifyViaPackageManager(): Boolean {
        try {
            val info = packageManager.getPackageInfo(
                packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            val digest = MessageDigest.getInstance("SHA-256")
            return signers.any { sig ->
                digest.reset()
                digest.digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) } == EXPECTED_CERT_HASH
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Read the APK V2/V3 signing block directly from the file on disk,
     * extract the signer certificate, and verify its SHA-256 hash.
     * This bypasses any Java-level PackageManager hooks (e.g. MT Manager).
     */
    private fun verifyViaApkFile(): Boolean {
        try {
            val apkPath = applicationInfo.sourceDir ?: return false
            RandomAccessFile(apkPath, "r").use { raf ->
                val cdOffset = findCentralDirectoryOffset(raf) ?: return false
                val pairsData = readSigningBlockPairs(raf, cdOffset) ?: return false
                return verifyCertInSigningBlock(pairsData)
            }
        } catch (_: Exception) {
            return false
        }
    }

    /** Locate the Central Directory by finding the End of Central Directory record. */
    private fun findCentralDirectoryOffset(raf: RandomAccessFile): Long? {
        val fileSize = raf.length()
        // EOCD can be at most 22 + 65535 bytes from the end of the file
        val maxSearch = minOf(fileSize, 65557L)
        val buf = ByteArray(maxSearch.toInt())
        raf.seek(fileSize - maxSearch)
        raf.readFully(buf)
        // Search for EOCD magic 0x06054b50 from the end
        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() &&
                buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
            ) {
                // CD offset is at EOCD + 16 (uint32 LE)
                return ByteBuffer.wrap(buf, i + 16, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            }
        }
        return null
    }

    /** Read the APK Signing Block's ID-value pairs data just before the Central Directory. */
    private fun readSigningBlockPairs(raf: RandomAccessFile, cdOffset: Long): ByteArray? {
        if (cdOffset < 32) return null
        // Verify magic "APK Sig Block 42" at cdOffset - 16
        raf.seek(cdOffset - 16)
        val magic = ByteArray(16)
        raf.readFully(magic)
        if (!magic.contentEquals(APK_SIG_BLOCK_MAGIC)) return null
        // Read block size at cdOffset - 24 (uint64 LE)
        // "size of block in bytes (excluding this field)" includes pairs + trailing size(8) + magic(16)
        raf.seek(cdOffset - 24)
        val sizeBuf = ByteArray(8)
        raf.readFully(sizeBuf)
        val blockSize = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).long
        if (blockSize < 24 || cdOffset - blockSize < 8) return null
        // Pairs data sits between the two size fields:
        //   starts at cdOffset - blockSize (after the leading 8-byte size field)
        //   ends at cdOffset - 24 (before the trailing 8-byte size field)
        val pairsLen = (blockSize - 24).toInt()
        raf.seek(cdOffset - blockSize)
        val data = ByteArray(pairsLen)
        raf.readFully(data)
        return data
    }

    /** Parse signing block pairs, find V2/V3 signer, extract and verify the certificate. */
    private fun verifyCertInSigningBlock(data: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 12) { // need at least 8 (pair size) + 4 (id)
            val pairSize = buf.long
            if (pairSize < 4 || pairSize > buf.remaining()) break
            val id = buf.int
            val valueSize = (pairSize - 4).toInt()
            if (id == APK_SIG_SCHEME_V3_ID || id == APK_SIG_SCHEME_V31_ID ||
                id == APK_SIG_SCHEME_V2_ID
            ) {
                val valueBytes = ByteArray(valueSize)
                buf.get(valueBytes)
                return extractCertAndVerify(valueBytes)
            } else {
                buf.position(buf.position() + valueSize)
            }
        }
        return false
    }

    /**
     * Extract the first signer's first certificate from the V2/V3 signer block
     * and verify its SHA-256 against EXPECTED_CERT_HASH.
     *
     * Signer block structure (all uint32 LE length-prefixed):
     *   [signers-sequence] → [signer] → [signed-data] → skip [digests] → [certificates] → [cert DER]
     */
    private fun extractCertAndVerify(data: ByteArray): Boolean {
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.int // signers sequence length
            buf.int // first signer length
            buf.int // signed data length
            // Skip digests
            val digestsLen = buf.int
            buf.position(buf.position() + digestsLen)
            // Certificates
            buf.int // certificates sequence length
            val certLen = buf.int
            val certBytes = ByteArray(certLen)
            buf.get(certBytes)
            // Compute SHA-256 and compare
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(certBytes)
                .joinToString("") { "%02x".format(it) }
            return hash == EXPECTED_CERT_HASH
        } catch (_: Exception) {
            return false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AudioGuardService.CHANNEL_ID,
            "读屏声音保护",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示读屏声音保护运行状态"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
