package io.github.gighi947.ankeshelf.data

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 统一备份包（与 Windows app/backup.py 同格式 ank-backup/1）：
 * zip 内含 manifest.json（format/created_at/app_version/files[{name,version,size,sha256}]）
 * 与五份 JSON 存储。导入前只读验证；目标已有数据时默认不覆盖，需显式确认。
 */
object Backup {
    const val FORMAT = "ank-backup/1"

    val STORE_NAMES = listOf("shelf", "progress", "settings", "annotations", "statistics")

    data class FileInfo(
        val name: String,
        val version: Int?,
        val size: Long,
        val sha256: String,
    )

    data class VerifyResult(
        val ok: Boolean,
        val errors: List<String>,
        val files: List<FileInfo>,
    )

    data class RestoreResult(
        val ok: Boolean,
        val errors: List<String>,
        val restored: List<String>,
        val needsOverwrite: Boolean = false,
        val existing: List<String> = emptyList(),
    )

    @Serializable
    private data class BackupFileEntry(
        val name: String,
        val version: Int? = null,
        val size: Long = 0,
        val sha256: String = "",
    )

    @Serializable
    private data class BackupManifest(
        val format: String,
        val created_at: String,
        val app_version: String,
        val files: List<BackupFileEntry> = emptyList(),
    )

    fun createBackupZip(zipFile: File, paths: Map<String, File>, appVersion: String): List<String> {
        zipFile.parentFile?.mkdirs()
        val infos = mutableListOf<BackupFileEntry>()
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            for (name in STORE_NAMES) {
                val src = paths[name] ?: continue
                if (!src.isFile) continue
                val bytes = src.readBytes()
                zos.putNextEntry(ZipEntry("$name.json"))
                zos.write(bytes)
                zos.closeEntry()
                infos += BackupFileEntry(
                    name = "$name.json",
                    version = schemaVersion(bytes),
                    size = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                )
            }
            val manifest = BackupManifest(
                format = FORMAT,
                created_at = nowIso(),
                app_version = appVersion,
                files = infos,
            )
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(Shelf.json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
            zos.closeEntry()
        }
        return infos.map { it.name }
    }

    fun verifyBackupZip(zipFile: File): VerifyResult {
        val errors = mutableListOf<String>()
        val files = mutableListOf<FileInfo>()
        try {
            ZipFile(zipFile).use { zf ->
                val names = zf.entries().asSequence().map { it.name }.toSet()
                if ("manifest.json" !in names) {
                    return VerifyResult(false, listOf("缺少 manifest.json"), emptyList())
                }
                val manifest = runCatching {
                    Shelf.json.decodeFromString<BackupManifest>(
                        zf.getInputStream(zf.getEntry("manifest.json")).readBytes().decodeToString(),
                    )
                }.getOrNull()
                if (manifest == null) {
                    return VerifyResult(false, listOf("manifest.json 无法解析"), emptyList())
                }
                if (manifest.format != FORMAT) {
                    return VerifyResult(false, listOf("不支持的备份格式：${manifest.format}"), emptyList())
                }
                for (entry in manifest.files) {
                    val ze = zf.getEntry(entry.name)
                    if (ze == null) {
                        errors += "缺少条目：${entry.name}"
                        continue
                    }
                    val bytes = zf.getInputStream(ze).readBytes()
                    if (entry.sha256.isNotEmpty() && sha256(bytes) != entry.sha256) {
                        errors += "校验和不匹配：${entry.name}"
                        continue
                    }
                    val version = schemaVersion(bytes)
                    if (version == null) {
                        errors += "JSON 无法解析或缺少版本字段：${entry.name}"
                        continue
                    }
                    files += FileInfo(entry.name, version, bytes.size.toLong(), sha256(bytes))
                }
            }
        } catch (e: Exception) {
            return VerifyResult(false, listOf(e.toString()), emptyList())
        }
        return VerifyResult(errors.isEmpty(), errors, files)
    }

    fun restoreBackupZip(zipFile: File, paths: Map<String, File>, overwrite: Boolean = false): RestoreResult {
        val check = verifyBackupZip(zipFile)
        if (!check.ok) {
            return RestoreResult(false, check.errors, emptyList())
        }
        val existing = check.files
            .filter { f -> f.name.endsWith(".json") && paths[f.name.removeSuffix(".json")]?.exists() == true }
            .map { it.name }
        if (existing.isNotEmpty() && !overwrite) {
            return RestoreResult(false, listOf("目标数据已存在，需显式确认覆盖"), emptyList(), true, existing)
        }
        val restored = mutableListOf<String>()
        ZipFile(zipFile).use { zf ->
            for (f in check.files) {
                val key = f.name.removeSuffix(".json")
                val target = paths[key] ?: continue
                val bytes = zf.getInputStream(zf.getEntry(f.name)).readBytes()
                atomicWriteText(target, bytes.decodeToString())
                restored += f.name
            }
        }
        return RestoreResult(true, emptyList(), restored)
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02X".format(it) }

    private fun schemaVersion(data: ByteArray): Int? = runCatching {
        val obj = Shelf.json.parseToJsonElement(data.decodeToString()).jsonObject
        obj["version"]?.jsonPrimitive?.intOrNull
            ?: obj["settings_version"]?.jsonPrimitive?.intOrNull
    }.getOrNull()
}
