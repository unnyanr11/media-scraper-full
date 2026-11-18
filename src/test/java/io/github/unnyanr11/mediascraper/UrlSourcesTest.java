package io.github.unnyanr11.mediascraper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UrlSourcesTest {
    @Test void clipboardImportRemovesDuplicates() {
        assertEquals(2, UrlSources.fromText("https://a.example/a\nhttps://a.example/a https://b.example/b").size());
    }

    @Test void paddedSequence() {
        assertEquals(java.util.List.of("https://example.org/image001.jpg",
            "https://example.org/image002.jpg", "https://example.org/image003.jpg"),
            UrlSources.expandSequence("https://example.org/image{001..003}.jpg"));
    }

    @Test void boundedSequence() {
        assertThrows(IllegalArgumentException.class,
            () -> UrlSources.expandSequence("https://example.org/{00000..99999}.png"));
    }
}
