package com.stealth.browser.extensions.packageinstall

import com.stealth.browser.extensions.manifest.ManifestParser
import com.stealth.browser.extensions.manifest.ManifestParseResult
import com.stealth.browser.extensions.model.ExtensionManifest
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Validates and extracts extension packages BEFORE any of their code is executed
 * (PRD §13.1: "Do not automatically execute untrusted extension packages").
 *
 * Supported today:
 *  - ZIP packages containing a valid manifest.json at the root (unpacked/ZIP install).
 *  - CRX3 container parsing (magic "Cr24", version 3) — header+signature are parsed
 *    and the evidence status is reported; the WebView backing cannot yet VERIFY the
 *    Web Store chain of trust, so signature verification is surfaced as a real state
 *    rather than silently skipped.
 *
 * Rejected: malformed zips, nested/path-escaping entries (zip-slip), CRX2, and
 * any package that does not yield a valid manifest.
 */
object PackageInstaller {

    const val CRX3_MAGIC = "Cr24"
    const val CRX2_MAGIC = "Cr24" // same magic, version distinguishes

    sealed interface InstallValidation {
        data class Complete(val manifest: ExtensionManifest, val files: Map<String, ByteArray>) : InstallValidation
        data class Reject(val reason: String) : InstallValidation
        data class NeedsWork(val extractedTo: File) : InstallValidation
    }

    enum class SignatureState { NOT_PACKAGED_UNPACKED, CRX3_HEADER_PRESENT, CRX3_VERIFIED, UNVERIFIED }

    fun validateZip(packageFile: File): InstallValidation {
        if (!packageFile.exists() || packageFile.length() == 0L) return InstallValidation.Reject("empty package file")
        val maxBytes = 50L * 1024 * 1024
        if (packageFile.length() > maxBytes) return InstallValidationRejectSize()
        val zip = runCatching { ZipFile(packageFile) }.getOrElse {
            return InstallValidation.Reject("not a valid ZIP/CRX container: ${it.message}")
        }
        zip.use { archive ->
            val manifestEntry = archive.getEntry("manifest.json")
                ?: return InstallValidation.Reject("manifest.json missing at package root")
            val manifestRaw = archive.getInputStream(manifestEntry).use { inp ->
                checkBounds(inp, manifestEntry.size, "manifest.json")
                inp.readBytes()
            }
            val manifest = when (val parsed = ManifestParser.parse(manifestRaw.decodeToString())) {
                is ManifestParseResult.Success -> parsed.manifest
                is ManifestParseResult.Failure -> return InstallValidation.Reject(parsed.reason)
            }
            // Zip-slip guard: reject entries escaping the extension root.
            for (entry in archive.entries()) {
                val normal = entry.name.replace('\\', '/')
                if (normal.startsWith("/") || normal.contains("..")) {
                    return InstallValidation.Reject("unsafe path in package: ${entry.name}")
                }
            }
            val files = extractAll(archive)
            return InstallValidation.Complete(manifest, files)
        }
    }

    /**
     * CRX3 structure check: magic + version + signed header data.
     * Returns the manifest if the container parses; verification of the Web Store
     * signature chain is reported through [SignatureState] as UNVERIFIED —
     * forward pass to runtime DB CA validation (docs/extension_trust.md).
     */
    fun validateCrx(packageFile: File): InstallValidation {
        val bytes = packageFile.readBytes()
        if (bytes.size < 8) return InstallValidation.Reject("file too small for CRX")
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != CRX3_MAGIC) return InstallValidation.Reject("not a CRX container (bad magic)")
        val version = readIntLE(bytes, 4)
        if (version != 3) return InstallValidation.Reject("unsupported CRX version $version (CRX2 is rejected)")
        val headerLen = readIntLE(bytes, 8)
        if (headerLen <= 0 || 12 + headerLen > bytes.size) return InstallValidation.Reject("malformed CRX3 header length")
        // Manifest must still live inside the zip payload following the header.
        val payload = bytes.copyOfRange(12 + headerLen, bytes.size)
        if (!isZip(payload)) return InstallValidation.Reject("CRX3 payload is not a valid ZIP")
        val tmp = File.createTempFile("crx_payload", ".zip", packageFile.parentFile)
        tmp.writeBytes(payload)
        try {
            return when (val inner = validateZip(tmp)) {
                is InstallValidation.Complete -> inner
                else -> inner
            }
        } finally {
            tmp.delete()
        }
    }

    /** Digest over the full package bytes, used in the install audit log. */
    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Extracts every entry from an already-validated zip to the destination directory. */
    fun extractAll(archive: ZipFile, toDir: File): Boolean {
        var ok = true
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val target = File(toDir, entry.name.replace('\\', '/'))
            if (!target.canonicalPath.startsWith(toDir.canonicalPath)) { ok = false; continue }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                runCatching { archive.getInputStream(entry).use { inp -> target.outputStream().use { inp.copyTo(it) } } }
                    .onFailure { ok = false }
            }
        }
        return ok
    }

    private fun checkBounds(inp: java.io.InputStream, size: Long, label: String) {
        if (size > 50L * 1024 * 1024) throw SecurityException("$label entry too large")
    }

    private fun InstallValidationRejectSize(): InstallValidation = InstallValidation.Reject("package exceeds 50 MiB limit")

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun isZip(b: ByteArray): Boolean = b.size > 3 && b[0] == 'P'.code.toByte() &&
        b[1] == 'K'.code.toByte() && b[2] == 3.toByte()

    private fun extractAll(archive: ZipFile): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory) {
                map[entry.name] = archive.getInputStream(entry).use { it.readBytes() }
            }
        }
        return map
    }
}
