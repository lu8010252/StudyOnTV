package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04
    )
    private val jpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x10, 0x20, 0x30
    )

    @Test
    fun saveDataUrlDeduplicatesBySha256AndMapsJpegToJpg() {
        val dir = temp.newFolder("media")
        val pngUrl = "data:image/png;base64," + MediaStore.encodeBase64(pngBytes)
        val path1 = MediaStore.saveDataUrl(dir, pngUrl)
        val path2 = MediaStore.saveDataUrl(dir, "  $pngUrl  ")
        assertTrue(MediaStore.isMediaPath(path1))
        assertTrue(path1.endsWith(".png"))
        assertEquals(path1, path2)
        assertEquals(1, dir.listFiles()?.count { it.isFile })

        val jpegUrl = "data:image/jpeg;base64," + MediaStore.encodeBase64(jpegBytes)
        val jpgPath = MediaStore.saveDataUrl(dir, jpegUrl)
        assertTrue(jpgPath.endsWith(".jpg"))
        assertTrue(MediaStore.isMediaPath(jpgPath))
        assertEquals(2, dir.listFiles()?.count { it.isFile })

        val readBack = MediaStore.readMedia(dir, jpgPath.removePrefix(MediaStore.PREFIX))
        assertNotNull(readBack)
        assertEquals("image/jpeg", readBack!!.second)
        assertArrayEquals(jpegBytes, readBack.first)
    }

    @Test
    fun pathTraversalAndMalformedNamesAreStrictlyRejected() {
        val dir = temp.newFolder("media_sec")
        val secret = File(temp.root, "a".repeat(64) + ".png").apply { writeBytes(pngBytes) }
        assertTrue(secret.exists())

        val attacks = listOf(
            "../" + secret.name,
            "..\\" + secret.name,
            "/api/media/" + "a".repeat(64) + ".png",
            "A".repeat(64) + ".png",
            "a".repeat(63) + ".png",
            "a".repeat(64) + ".svg",
            "a".repeat(64) + ".png.bak",
            ""
        )
        for (attack in attacks) {
            assertFalse("Expected invalid media name: $attack", MediaStore.isValidName(attack))
            assertNull("readMedia must reject $attack", MediaStore.readMedia(dir, attack))
            assertFalse(MediaStore.isMediaPath(MediaStore.PREFIX + attack))
        }
    }

    @Test
    fun toDataUrlAndInlineQuestionMediaRoundTripAndHandleMissingFiles() {
        val dir = temp.newFolder("media_inline")
        val pngUrl = "data:image/png;base64," + MediaStore.encodeBase64(pngBytes)
        val jpgUrl = "data:image/jpeg;base64," + MediaStore.encodeBase64(jpegBytes)

        val pngPath = MediaStore.normalizeImageRef(dir, pngUrl)
        val jpgPath = MediaStore.normalizeImageRef(dir, jpgUrl)
        assertEquals("", MediaStore.normalizeImageRef(dir, "   "))
        assertEquals(pngPath, MediaStore.normalizeImageRef(dir, " $pngPath "))
        assertEquals("https://example.com/pic.webp", MediaStore.normalizeImageRef(dir, " https://example.com/pic.webp "))

        assertEquals(pngUrl, MediaStore.toDataUrl(dir, pngPath))
        assertEquals("https://example.com/pic.webp", MediaStore.toDataUrl(dir, "https://example.com/pic.webp"))

        val missingPath = MediaStore.PREFIX + "f".repeat(64) + ".png"
        assertEquals("", MediaStore.toDataUrl(dir, missingPath))

        val question = JSONObject()
            .put("q", "识图题")
            .put("image", pngPath)
            .put("images", JSONArray(listOf(pngPath, missingPath)))
            .put("optionImages", JSONArray(listOf("", jpgPath, missingPath)))

        val inlined = MediaStore.inlineQuestionMedia(dir, question)
        assertEquals(pngUrl, inlined.getString("image"))
        assertEquals(1, inlined.getJSONArray("images").length())
        assertEquals(pngUrl, inlined.getJSONArray("images").getString(0))
        // optionImages 必须保持位置对齐
        assertEquals(3, inlined.getJSONArray("optionImages").length())
        assertEquals("", inlined.getJSONArray("optionImages").getString(0))
        assertEquals("data:image/jpeg;base64," + MediaStore.encodeBase64(jpegBytes), inlined.getJSONArray("optionImages").getString(1))
        assertEquals("", inlined.getJSONArray("optionImages").getString(2))
    }

    @Test
    fun collectAndPruneRecognizesBothPlainAndAndroidEscapedSlashes() {
        val dir = temp.newFolder("media_prune")
        val p1 = MediaStore.saveDataUrl(dir, "data:image/png;base64," + MediaStore.encodeBase64(byteArrayOf(1, 2, 3)))
        val p2 = MediaStore.saveDataUrl(dir, "data:image/gif;base64," + MediaStore.encodeBase64(byteArrayOf(4, 5, 6)))
        val orphan = MediaStore.saveDataUrl(dir, "data:image/webp;base64," + MediaStore.encodeBase64(byteArrayOf(7, 8, 9)))

        val n1 = p1.removePrefix(MediaStore.PREFIX)
        val n2 = p2.removePrefix(MediaStore.PREFIX)
        val nOrphan = orphan.removePrefix(MediaStore.PREFIX)

        val referenced = mutableSetOf<String>()
        // 普通斜杠 + Android org.json 转义斜杠 \/api\/media\/...
        val json = """{"image":"$p1","drawing":"\/api\/media\/$n2"}"""
        MediaStore.collectMediaNamesFromJson(json, referenced)
        assertEquals(setOf(n1, n2), referenced)

        val removed = MediaStore.pruneUnreferenced(dir, referenced)
        assertEquals(1, removed)
        assertTrue(File(dir, n1).exists())
        assertTrue(File(dir, n2).exists())
        assertFalse(File(dir, nOrphan).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedDecodedBytes() {
        val dir = temp.newFolder("media_limit")
        val dataUrl = "data:image/png;base64," + MediaStore.encodeBase64(ByteArray(1024) { 0x42 })
        MediaStore.saveDataUrl(dir, dataUrl, maxDecodedBytes = 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsJavascriptScheme() {
        val dir = temp.newFolder("media_scheme")
        MediaStore.normalizeImageRef(dir, "javascript:alert(1)")
    }
}
