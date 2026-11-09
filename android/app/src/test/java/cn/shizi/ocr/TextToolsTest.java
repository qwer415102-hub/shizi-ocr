package cn.shizi.ocr;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TextToolsTest {
    @Test public void rangesAreSortedDeduplicated() {
        assertEquals(Arrays.asList(0, 1, 2, 7), TextTools.pages("3,1-2，2,8", 8));
    }
    @Test public void allPagesInSmallPdf() { assertEquals(Arrays.asList(0, 1, 2), TextTools.pages("", 3)); }
    @Test public void rejectsInvalidRanges() {
        for (String spec : new String[]{"0", "4", "2-1", "1,,2", "1-9999999999999999999", "x"}) {
            assertThrows(IllegalArgumentException.class, () -> TextTools.pages(spec, 3));
        }
    }
    @Test public void limitsPageWorkBeforeAllocating() {
        assertThrows(IllegalArgumentException.class, () -> TextTools.pages("1-2147483647", Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> TextTools.pages("", 101));
    }
    @Test public void translationChunksPreserveUnicodeAndByteBudget() {
        String text = ("Meeting starts tomorrow. 中文测试😀\n").repeat(100) + "x".repeat(1000);
        var chunks = TextTools.chunks(text);
        assertEquals(text, String.join("", chunks));
        for (String part : chunks) {
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= 450);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
    @Test public void detectsMixedLanguageEnglish() {
        assertTrue(TextTools.hasEnglish("中文 and English"));
        assertFalse(TextTools.hasEnglish("中文 12345"));
    }
}
