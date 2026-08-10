package io.github.gighi947.ankeshelf.contracts

import io.github.gighi947.ankeshelf.data.TextExtractor
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** B1 契约：Kotlin TextExtractor 与 contracts/text/text-cases.json 对照。 */
class ContractTextTest {

    private val cases = run {
        val root = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(contractsRoot().resolve("text/text-cases.json").readText(Charsets.UTF_8))
            .jsonObject
        root.getValue("cases").jsonArray
    }

    @Test
    fun `text cases match canonical except documented divergences`() {
        for (c in cases) {
            val obj = c.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val html = obj.getValue("html").jsonPrimitive.content
            val expected = obj.getValue("expected").jsonPrimitive.content
            val actual = TextExtractor.extractDomText(html)
            assertEquals("case $id", expected, actual)
        }
    }

    @Test
    fun `native book fixture plaintext matches expected`() {
        val fixture = contractsRoot().resolve("fixtures/native-book/basic-nga")
        val expected = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(fixture.resolve("expected_plaintext.json").readText(Charsets.UTF_8))
            .jsonObject
        for ((name, value) in expected) {
            val html = File(fixture, "chapters/$name").readText(Charsets.UTF_8)
            assertEquals("chapter $name", value.jsonPrimitive.content, TextExtractor.extractDomText(html))
        }
    }

    private fun contractsRoot(): File {
        System.getProperty("contracts.dir")?.let { return File(it) }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("contracts/ 未找到；可用 -Dcontracts.dir=... 指定")
    }
}
