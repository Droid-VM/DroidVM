package cn.classfun.droidvm.lib.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static cn.classfun.droidvm.lib.utils.StringUtils.GROUPED_PASSWORD_DIGITS;
import static cn.classfun.droidvm.lib.utils.StringUtils.GROUPED_PASSWORD_LOWER;
import static cn.classfun.droidvm.lib.utils.StringUtils.GROUPED_PASSWORD_SYMBOLS;
import static cn.classfun.droidvm.lib.utils.StringUtils.GROUPED_PASSWORD_UPPER;
import static cn.classfun.droidvm.lib.utils.StringUtils.SHELL_SAFE_PASSWORD_SYMBOLS;
import static cn.classfun.droidvm.lib.utils.StringUtils.generateGroupedPassword;
import static cn.classfun.droidvm.lib.utils.StringUtils.isShellSafePassword;
import static cn.classfun.droidvm.lib.utils.StringUtils.shellSafePasswordFilter;

import org.junit.Test;

public class ShellSafePasswordTest {
    @Test
    public void generatedPasswordFollowsGroupedPattern() {
        for (int round = 0; round < 200; round++) {
            var p = generateGroupedPassword();
            assertEquals(11, p.length());
            for (int i = 0; i < 2; i++)
                assertTrue(GROUPED_PASSWORD_UPPER.indexOf(p.charAt(i)) >= 0);
            for (int i = 2; i < 5; i++)
                assertTrue(GROUPED_PASSWORD_LOWER.indexOf(p.charAt(i)) >= 0);
            for (int i = 5; i < 9; i++)
                assertTrue(GROUPED_PASSWORD_DIGITS.indexOf(p.charAt(i)) >= 0);
            for (int i = 9; i < 11; i++)
                assertTrue(GROUPED_PASSWORD_SYMBOLS.indexOf(p.charAt(i)) >= 0);
            assertTrue(isShellSafePassword(p));
        }
    }

    @Test
    public void generatorCharsetsStayInsideShellSafeWhitelist() {
        for (char c : GROUPED_PASSWORD_SYMBOLS.toCharArray())
            assertTrue(SHELL_SAFE_PASSWORD_SYMBOLS.indexOf(c) >= 0);
        // The grouped charsets deliberately omit ambiguous glyphs.
        for (char c : "IOlo01".toCharArray()) {
            assertFalse(GROUPED_PASSWORD_UPPER.indexOf(c) >= 0);
            assertFalse(GROUPED_PASSWORD_LOWER.indexOf(c) >= 0);
            assertFalse(GROUPED_PASSWORD_DIGITS.indexOf(c) >= 0);
        }
    }

    @Test
    public void gateRejectsShellMetacharacters() {
        assertTrue(isShellSafePassword("KRvxz4821#%"));
        assertTrue(isShellSafePassword(""));
        for (var bad : new String[]{
            "pass'word", "pass\"word", "pass\\word", "pass$word", "pass`word",
            "pass word", "pass\nword", "pass\tword", "pass;word", "pass|word",
            "pass&word", "pass<word", "pass>word", "pass(word)", "\u5bc6\u78bc"
        }) assertFalse(bad, isShellSafePassword(bad));
    }

    @Test
    public void filterStripsUnsafeCharactersOnly() {
        var filter = shellSafePasswordFilter();
        // Fully safe input passes through untouched (null = keep original).
        assertNull(filter.filter("Ab1!@#%^*+-=_.,:?", 0, 17, null, 0, 0));
        // Unsafe characters are dropped, safe ones kept, order preserved.
        assertEquals("Ab1", filter.filter("A'b\"1$", 0, 6, null, 0, 0).toString());
        assertEquals("", filter.filter("'\"`$\\", 0, 5, null, 0, 0).toString());
        // Only the [start, end) slice is considered.
        assertNull(filter.filter("$ab$", 1, 3, null, 0, 0));
        assertEquals("ab", filter.filter("$a$b", 1, 4, null, 0, 0).toString());
    }
}
