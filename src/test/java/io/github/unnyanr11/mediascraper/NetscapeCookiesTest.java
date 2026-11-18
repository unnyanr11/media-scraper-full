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
}
