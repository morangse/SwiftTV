package app.swifttv.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandHintsTest {

    @Test
    fun `known manufacturer strings are normalised`() {
        assertEquals("小米", BrandHints.displayBrand("Xiaomi Communications Co Ltd"))
        assertEquals("海信", BrandHints.displayBrand("Hisense"))
        assertEquals("索尼", BrandHints.displayBrand("Sony Corporation"))
        assertEquals("TCL", BrandHints.displayBrand("TCL"))
    }

    @Test
    fun `unknown manufacturer keeps original text`() {
        assertEquals("Acme TV", BrandHints.displayBrand("Acme TV"))
    }

    @Test
    fun `tv detection uses characteristics and model`() {
        assertTrue(BrandHints.isTvLike("tv", null, null))
        assertTrue(BrandHints.isTvLike(null, "SmartTV 4K", null))
        assertTrue(BrandHints.isTvLike("box", null, null))
        assertFalse(BrandHints.isTvLike("phone", "Pixel 8", "Google"))
    }

    @Test
    fun `setup hint mentions the detected brand`() {
        assertTrue(BrandHints.setupHint("Xiaomi", "MiTV", "L65M5").contains("小米"))
        assertTrue(BrandHints.setupHint("Sony", "BRAVIA", "KD-55").contains("索尼"))
        assertTrue(BrandHints.setupHint(null, null, null).contains("通用方法"))
    }
}
