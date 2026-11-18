package io.github.unnyanr11.mediascraper;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class NetscapeCookiesTest {
    @Test void loadsNetscapeAndHttpOnlyLines() throws Exception {
        var file = Files.createTempFile("cookie-test", ".txt");
        try {
            Files.writeString(file, "# Netscape HTTP Cookie File\n"
                + ".example.com\tTRUE\t/\tTRUE\t0\tkey\tvalue\n"
                + "#HttpOnly_.example.com\tTRUE\t/\tFALSE\t0\tsecret\tval\n");
            var cookies = NetscapeCookies.load(file);
            assertEquals(2, cookies.size());
            assertEquals("key", cookies.get(0).name);
            assertTrue(cookies.get(1).httpOnly);
        } finally {Files.deleteIfExists(file);}
    }

    @Test void acceptsMultilinePasteWithHeadersAndPreservesEncodedValues() throws Exception {
        String pasted = "\uFEFF" + String.join("\r\n",
            "# Netscape HTTP Cookie File",
            "# Generated file",
            "",
            ".example.org\tTRUE\t/\tTRUE\t4102444800\tsessionid\ttest%3Aabc%2Bdef",
            "#HttpOnly_.example.org\tTRUE\t/\tTRUE\t0\tempty\t",
            ".example.org\tTRUE\t/\tTRUE\t1\told\texpired");
        var input = CookieInput.paste(pasted);
        assertEquals(2, input.load().size());
        assertEquals("test%3Aabc%2Bdef", input.load().getFirst().value);
        assertTrue(input.load().getLast().httpOnly);
        assertEquals("", input.load().getLast().value);
        assertFalse(input.toString().contains("test%3Aabc"));
        var bad = assertThrows(java.io.IOException.class,
            () -> CookieInput.paste("# title\nmalformed secret"));
        assertEquals("Invalid Netscape cookie at line 2", bad.getMessage());
        assertThrows(java.io.IOException.class, () -> CookieInput.paste("# header only"));
    }
}
