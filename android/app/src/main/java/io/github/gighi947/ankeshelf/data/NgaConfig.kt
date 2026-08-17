package io.github.gighi947.ankeshelf.data

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** NGA 登录配置快照（uid/cid 为敏感凭据，仅存应用私有目录）。 */
data class NgaConfigData(
    val uid: String = "",
    val cid: String = "",
    val ua: String = NgaConfig.DEFAULT_UA,
    val baseUrl: String = "https://bbs.nga.cn",
    val configured: Boolean = false,
)

/**
 * NGA 配置存取（对齐桌面 app/nga_config.py）：
 * 私有 ini 文件 [network] 段，键名 ngaPassportUid / ngaPassportCid / ua / base_url。
 */
class NgaConfig(private val file: File) {

    private val lock = ReentrantLock()

    fun ensure() {
        lock.withLock {
            if (!file.isFile) writeTemplate()
        }
    }

    fun load(): NgaConfigData {
        ensure()
        val raw = lock.withLock { readIni() }
        val uid = raw["ngaPassportUid"] ?: ""
        val cid = raw["ngaPassportCid"] ?: ""
        val ua = (raw["ua"] ?: "").ifEmpty { DEFAULT_UA }
        return NgaConfigData(
            uid = uid,
            cid = cid,
            ua = ua,
            baseUrl = raw["base_url"] ?: "https://bbs.nga.cn",
            configured = isReal(uid, cid, ua),
        )
    }

    /** 保存用户编辑的配置（只更新非 null 字段）。 */
    fun save(patch: NgaConfigPatch): NgaConfigData {
        ensure()
        lock.withLock {
            val raw = readIni().toMutableMap()
            patch.uid?.let { raw["ngaPassportUid"] = it.trim() }
            patch.cid?.let { raw["ngaPassportCid"] = it.trim() }
            patch.ua?.let { raw["ua"] = it.trim() }
            patch.baseUrl?.let { raw["base_url"] = it.trim() }
            writeIni(raw)
        }
        return load()
    }

    /** 删除已保存的 NGA 凭据，重置为占位模板。 */
    fun clear(): NgaConfigData {
        lock.withLock { writeTemplate() }
        return load()
    }

    private fun readIni(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyMap()
        }
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("#") || t.startsWith("[")) continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val key = t.substring(0, eq).trim()
            var value = t.substring(eq + 1).trim()
            value = value.removePrefix("`").removeSuffix("`")
            out[key] = value
        }
        return out
    }

    private fun writeIni(raw: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("[network]\n")
        sb.append("base_url=").append(raw["base_url"] ?: "https://bbs.nga.cn").append("\n")
        sb.append("ua=").append(raw["ua"] ?: DEFAULT_UA).append("\n")
        sb.append("ngaPassportUid=").append(raw["ngaPassportUid"] ?: "").append("\n")
        sb.append("ngaPassportCid=").append(raw["ngaPassportCid"] ?: "").append("\n")
        file.parentFile?.mkdirs()
        atomicWriteText(file, sb.toString())
    }

    private fun writeTemplate() {
        writeIni(
            mapOf(
                "base_url" to "https://bbs.nga.cn",
                "ua" to DEFAULT_UA,
                "ngaPassportUid" to "",
                "ngaPassportCid" to "",
            ),
        )
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val BANNED = setOf("<;MODIFY_ME;>", "MODIFY_ME", "")

        private fun isReal(uid: String, cid: String, ua: String): Boolean =
            uid !in BANNED && cid !in BANNED && ua !in BANNED
    }
}

data class NgaConfigPatch(
    val uid: String? = null,
    val cid: String? = null,
    val ua: String? = null,
    val baseUrl: String? = null,
)
