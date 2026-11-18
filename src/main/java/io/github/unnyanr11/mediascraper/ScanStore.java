package io.github.unnyanr11.mediascraper;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Persists previously discovered URLs locally, without persisting cookie secrets. */
public final class ScanStore implements AutoCloseable {
    private final Connection connection;

    public ScanStore() throws SQLException {
        try {
            Path home = Path.of(System.getProperty("user.home"), ".media-scraper-full");
            Files.createDirectories(home);
            connection = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("history.sqlite").toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new SQLException("Unable to create local history folder", e);
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS discovered (
                  source_url TEXT NOT NULL,
                  media_url TEXT NOT NULL,
                  media_type TEXT NOT NULL,
                  post_url TEXT NOT NULL,
                  first_seen TEXT NOT NULL,
                  last_seen TEXT NOT NULL,
                  downloaded INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (source_url, media_type, media_url)
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sources (
                  source_url TEXT PRIMARY KEY,
                  last_scan TEXT NOT NULL
                )
                """);
        }
    }

    public synchronized void rememberSource(String source) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
            INSERT INTO sources (source_url, last_scan) VALUES (?, ?)
            ON CONFLICT(source_url) DO UPDATE SET last_scan = excluded.last_scan
            """)) {
            stmt.setString(1, source);
            stmt.setString(2, Instant.now().toString());
            stmt.executeUpdate();
        }
    }

    public synchronized List<String> sources() throws SQLException {
        List<String> sources = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT source_url FROM sources ORDER BY last_scan DESC");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) sources.add(rs.getString(1));
        }
        return sources;
    }

    public synchronized boolean record(String source, MediaItem item) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT OR IGNORE INTO discovered
            (source_url, media_url, media_type, post_url, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            insert.setString(1, source);
            insert.setString(2, item.url());
            insert.setString(3, item.type());
            insert.setString(4, item.postUrl());
            insert.setString(5, now);
            insert.setString(6, now);
            if (insert.executeUpdate() > 0) return true;
        }
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE discovered SET last_seen = ? WHERE source_url = ? AND media_type = ? AND media_url = ?
            """)) {
            update.setString(1, now);
            update.setString(2, source);
            update.setString(3, item.type());
            update.setString(4, item.url());
            update.executeUpdate();
        }
        return false;
    }

    public synchronized boolean downloaded(String source, MediaItem item) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT downloaded FROM discovered WHERE source_url = ? AND media_type = ? AND media_url = ?
            """)) {
            query.setString(1, source);
            query.setString(2, item.type());
            query.setString(3, item.url());
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    public synchronized void markDownloaded(String source, MediaItem item) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE discovered SET downloaded = 1 WHERE source_url = ? AND media_type = ? AND media_url = ?
            """)) {
            update.setString(1, source);
            update.setString(2, item.type());
            update.setString(3, item.url());
            update.executeUpdate();
        }
    }

    public synchronized List<MediaItem> history(String source) throws SQLException {
        List<MediaItem> items = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT post_url, media_url, media_type FROM discovered WHERE source_url = ? ORDER BY first_seen
            """)) {
            query.setString(1, source);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) items.add(new MediaItem(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return items;
    }

    @Override public void close() throws SQLException { connection.close(); }
}
