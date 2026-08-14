package io.github.gighi947.ankeshelf.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private fun tempDir(): File = File(kotlin.io.path.createTempDirectory("backup-").toFile(), "data").apply { mkdirs() }

    private fun makeStores(dir: File): Map<String, File> {
        val paths = mutableMapOf<String, File>()
        for (name in Backup.STORE_NAMES) {
            val f = File(dir, "$name.json")
            f.writeText("{\"version\":1,\"$name\":\"$name\"}", Charsets.UTF_8)
            paths[name] = f
        }
        return paths
    }

    @Test
    fun `create then verify ok`() {
        val dir = tempDir()
        val zip = File(dir.parentFile, "backup.zip")
        val names = Backup.createBackupZip(zip, makeStores(dir), "1.0.0")
        assertEquals(5, names.size)
        val check = Backup.verifyBackupZip(zip)
        assertTrue(check.ok.toString(), check.ok)
        assertEquals(5, check.files.size)
        dir.deleteRecursively()
    }

    @Test
    fun `tampered checksum fails`() {
        val dir = tempDir()
        val zip = File(dir.parentFile, "backup.zip")
        Backup.createBackupZip(zip, makeStores(dir), "1.0.0")
        // 重建 zip：把 manifest 里第一个条目 sha256 改成错误值
        val tampered = File(dir.parentFile, "tampered.zip")
        ZipOutputStream(tampered.outputStream()).use { zos ->
            java.util.zip.ZipFile(zip).use { zf ->
                for (e in zf.entries()) {
                    val bytes = zf.getInputStream(e).readBytes()
                    var out = bytes
                    if (e.name == "manifest.json") {
                        out = String(bytes).replaceFirst(
                            Regex("\"sha256\":\\s*\"[A-F0-9]{64}\""),
                            "\"sha256\":\"${"0".repeat(64)}\"",
                        ).encodeToByteArray()
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(out)
                    zos.closeEntry()
                }
            }
        }
        val check = Backup.verifyBackupZip(tampered)
        assertFalse(check.ok)
        assertTrue(check.errors.any { it.contains("校验和不匹配") })
        dir.deleteRecursively()
    }

    @Test
    fun `restore requires overwrite confirmation`() {
        val dir = tempDir()
        val zip = File(dir.parentFile, "backup.zip")
        Backup.createBackupZip(zip, makeStores(dir), "1.0.0")
        val targets = mutableMapOf<String, File>()
        for (name in Backup.STORE_NAMES) targets[name] = File(dir.parentFile, "restore-$name.json")

        val first = Backup.restoreBackupZip(zip, targets, overwrite = false)
        assertTrue(first.ok.toString(), first.ok)
        assertEquals(5, first.restored.size)

        val second = Backup.restoreBackupZip(zip, targets, overwrite = false)
        assertFalse(second.ok)
        assertTrue(second.needsOverwrite)
        assertEquals(5, second.existing.size)

        val third = Backup.restoreBackupZip(zip, targets, overwrite = true)
        assertTrue(third.ok.toString(), third.ok)
        assertEquals(5, third.restored.size)
        dir.deleteRecursively()
    }
}
