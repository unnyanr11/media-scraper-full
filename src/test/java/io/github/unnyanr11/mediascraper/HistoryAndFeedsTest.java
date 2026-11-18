package io.github.unnyanr11.mediascraper;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HistoryAndFeedsTest {
    @TempDir Path workspace;

    @Test void oldHistoryIsPreservedAndSignedUrlRotationKeepsOneItem() throws Exception {
        String source = "https://example.org/profile";
        String first = "https://cdn.example.org/image.jpg?sig=old";
        Path database = workspace.resolve("history.sqlite");
        try (var store = new ScanStore(database); var legacy = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var write = legacy.prepareStatement("INSERT INTO discovered VALUES (?,?,?,?,?,?,1)")) {
                for (int i : List.of(1, 4)) write.setString(i, source);
                write.setString(2, first);
                write.setString(3, "image");
                write.setString(5, "2025-07-01T00:00:00Z");
                write.setString(6, "2025-07-02T00:00:00Z");
                write.executeUpdate();
            }
            var item = new MediaItem(source, first, "image", "s1", "", "original caption", "image1");
            assertFalse(store.record(source, item));
            assertTrue(store.downloaded(source, item));
            var updated = new MediaItem(source, first.replace("old", "new"), "image", "s1", "2025-07-01", "", "image1");
            assertFalse(store.record(source, updated));
            assertEquals(1, store.historyEntries(source).size());
            var entry = store.historyEntries(source).getFirst();
            assertEquals(updated.url(), entry.item().url());
            assertEquals("original caption", entry.item().caption());
            assertEquals("2025-07-01T00:00:00Z", entry.firstSeen());
            assertTrue(entry.downloaded());
            store.checkpoint(source, List.of(source + "/older"), List.of(source));
            assertEquals(List.of(source + "/older"), store.checkpoint(source).pending());
        }
    }

    @Test void readsPublicFeedMetadataAndOriginalAttachments() {
        var blue = PublicFeeds.parseBluesky(JsonParser.parseString("""
            {"cursor":"$next","feed":[{"post":{"uri":"at://did:plc:abc/app.bsky.feed.post/key",
            "record":{"text":"Caption","createdAt":"2025-06-14T20:00:00Z"},
            "embed":{"images":[{"thumb":"https://cdn.bsky.app/thumb","fullsize":"https://cdn.bsky.app/original"}]}}}]}
            """), "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?limit=50&actor=bsky.example");
        assertEquals(List.of("post", "image"), blue.items().stream().map(MediaItem::type).toList());
        assertEquals("https://cdn.bsky.app/original", blue.items().getLast().url());
        assertEquals("Caption", blue.items().getLast().caption());
        assertTrue(blue.next().endsWith("&cursor=%24next"));

        var mastodon = PublicFeeds.parseMastodon(JsonParser.parseString("""
            [{"id":"45","uri":"https://social.example/users/user/statuses/45","url":"https://social.example/@user/45",
              "created_at":"2025-04-01T01:02:03Z","content":"<p>Hi <b>there</b></p>",
              "media_attachments":[{"id":"99","type":"image","url":"https://cdn.example/original.png"}]}]
            """), "https://social.example/api/v1/accounts/4/statuses?limit=40");
        assertEquals("Hi there", mastodon.items().getLast().caption());
        assertEquals("99", mastodon.items().getLast().assetId());
        assertTrue(mastodon.next().endsWith("&max_id=45"));
    }

    @Test void supportsCustomSelectorsAndConfinesCookiesToTheirDomainAndPath() throws Exception {
        Path ruleFile = workspace.resolve("rules.txt");
        Files.writeString(ruleFile, "extract=image || img.full || data-source\nexclude=preview\n");
        SiteRules rules = SiteRules.load(ruleFile);
        assertEquals("data-source", rules.commands().getFirst().get("attribute"));
        assertFalse(rules.allows("https://example.org/preview"));
        Path cookies = workspace.resolve("cookies.txt");
        Files.writeString(cookies, """
            .example.org\tTRUE\t/gallery\tTRUE\t0\taccount\t
            example.org\tFALSE\t/\tFALSE\t0\thostOnly\t2
            """);
        var handler = new TransferCookies(NetscapeCookies.load(cookies));
        assertEquals("account=; hostOnly=2", handler.get(URI.create("https://example.org/gallery/a"), java.util.Map.of()).get("Cookie").getFirst());
        assertEquals("account=", handler.get(URI.create("https://cdn.example.org/gallery"), java.util.Map.of()).get("Cookie").getFirst());
        assertTrue(handler.get(URI.create("http://cdn.example.org/gallery"), java.util.Map.of()).isEmpty());
        assertTrue(handler.get(URI.create("https://cdn.example.org/gallery-extras"), java.util.Map.of()).isEmpty());
        assertTrue(handler.get(URI.create("https://badexample.org/gallery"), java.util.Map.of()).isEmpty());
    }
}
