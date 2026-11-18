package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class XmlTvParserTest {
    private val sampleXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "http://external.invalid/xmltv.dtd">
        <tv generator-info-name="test">
            <channel id="ch1">
                <display-name lang="zh">CCTV-1 综合</display-name>
            </channel>
            <channel id="HunanTV.cn">
                <display-name lang="zh">湖南卫视 HD</display-name>
            </channel>
            <programme start="20261118190000 +0800" stop="20261118193000 +0800" channel="ch1">
                <title lang="zh">新闻联播</title>
            </programme>
            <programme start="20261118193800 +0800" stop="20261118195500 +0800" channel="ch1">
                <title lang="zh">焦点访谈</title>
            </programme>
            <programme start="20261118120000 +0000" stop="20261118140000 +0000" channel="HunanTV.cn">
                <title lang="zh">黄金剧场</title>
            </programme>
        </tv>
    """.trimIndent()

    @Test fun parsesScheduleSafelyIgnoringExternalDtdAndMatchesCanonicalIdentities() {
        val targets = mapOf("cctv-1" to "CCTV-1", "hunan" to "湖南卫视", "zhejiang" to "浙江卫视")
        val now = XmlTvParser.parseTimestamp("20261118191500 +0800")!!
        val parsed = XmlTvParser.parse(
            sampleXml.byteInputStream(Charsets.UTF_8),
            targets,
            now - 3600_000L,
            now + 6 * 3600_000L
        )
        val cctv1 = XmlTvParser.query(parsed, "cctv-1", now)
        assertNotNull(cctv1)
        assertEquals("新闻联播", cctv1!!.current?.title)
        assertEquals("焦点访谈", cctv1.next?.title)
        assertEquals(0.5f, cctv1.current!!.progress(now), 0.01f)

        // 12:00 UTC == 20:00 +0800 (45 minutes after 19:15 +0800 -> upcoming)
        val hunan = XmlTvParser.query(parsed, "hunan", now)
        assertNotNull(hunan)
        assertNull(hunan!!.current)
        assertEquals("黄金剧场", hunan.next?.title)

        // Channel with no XMLTV data stays strictly null (no fake placeholder program)
        assertNull(XmlTvParser.query(parsed, "zhejiang", now))
    }
}
