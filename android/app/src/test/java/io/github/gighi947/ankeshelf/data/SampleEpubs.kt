package io.github.gighi947.ankeshelf.data

import java.io.File
import java.nio.file.Files

/** 复制桌面 tests/sample 的 EPUB 样本到临时目录供 JVM 测试使用。 */
object SampleEpubs {

    fun copy(name: String): File {
        val resource = "/samples/sample_$name.epub"
        val stream = SampleEpubs::class.java.getResourceAsStream(resource)
            ?: error("missing test resource: $resource")
        val dir = Files.createTempDirectory("ankeshelf-test").toFile()
        val out = File(dir, "sample_$name.epub")
        out.writeBytes(stream.use { it.readBytes() })
        return out
    }
}
