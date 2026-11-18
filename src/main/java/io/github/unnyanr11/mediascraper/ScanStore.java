package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Local history and transfer work list. Cookie contents never enter the database. */
public final class ScanStore implements AutoCloseable {
    private final Connection connection;

    public ScanStore() throws SQLException {
        this(Path.of(System.getProperty("user.home"), ".media-scraper-full", "history.sqlite"));
    }

    ScanStore(Path database) throws SQLException {
        try {
            Files.createDirectories(database.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new SQLException("Unable to create local history folder", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS discovered (
                  source_url TEXT NOT NULL, media_url TEXT NOT NULL, media_type TEXT NOT NULL,
                  post_url TEXT NOT NULL, first_seen TEXT NOT NULL, last_seen TEXT NOT NULL,
                  downloaded INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (source_url, media_type, media_url)
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sources (
                  source_url TEXT PRIMARY KEY, last_scan TEXT NOT NULL
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS source_profiles (
                  source_url TEXT PRIMARY KEY, category TEXT NOT NULL, mode TEXT NOT NULL
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS assets (
                  source TEXT NOT NULL, type TEXT NOT NULL, identity TEXT NOT NULL,
                  post_url TEXT NOT NULL, url TEXT NOT NULL, post_id TEXT NOT NULL,
                  published_at TEXT NOT NULL, caption TEXT NOT NULL, asset_id TEXT NOT NULL,
                  first_seen TEXT NOT NULL, last_seen TEXT NOT NULL,
                  downloaded INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (source, type, identity)
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS assets_url ON assets(source,type,url)");
            stmt.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS migrate_legacy_asset AFTER INSERT ON assets
                BEGIN
                    DELETE FROM discovered WHERE source_url=NEW.source AND media_type=NEW.type AND media_url=NEW.url;
                END
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS transfers (
                  source TEXT NOT NULL, type TEXT NOT NULL, identity TEXT NOT NULL, folder TEXT NOT NULL,
                  cookies TEXT, proxy TEXT NOT NULL, rules TEXT,
                  PRIMARY KEY (source, type, identity, folder)
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS crawl_checkpoint (
                  source TEXT NOT NULL, url TEXT NOT NULL, position INTEGER NOT NULL,
                  PRIMARY KEY (source, url)
                )
                """);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private PreparedStatement statement(String sql, Object... arguments) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement(sql);
        for (int i = 0; i < arguments.length; i++) stmt.setObject(i + 1, arguments[i]);
        return stmt;
    }

    private int update(String sql, Object... arguments) throws SQLException {
        try (var stmt = statement(sql, arguments)) {
            return stmt.executeUpdate();
        }
    }

    public synchronized void rememberSource(String source) throws SQLException {
        update("""
            INSERT INTO sources VALUES (?, ?) ON CONFLICT(source_url) DO UPDATE SET last_scan=excluded.last_scan
            """, source, Instant.now().toString());
    }

    public synchronized List<String> sources() throws SQLException {
        return strings("SELECT source_url FROM sources ORDER BY last_scan DESC");
    }

    record SavedSource(String url, String category, ScanService.Mode mode) {}

    synchronized List<SavedSource> savedSources() throws SQLException {
        List<SavedSource> result = new ArrayList<>();
        try (var stmt = statement("""
            SELECT s.source_url,COALESCE(p.category,''),COALESCE(p.mode,'AUTO')
            FROM sources s LEFT JOIN source_profiles p USING (source_url)
            ORDER BY p.category COLLATE NOCASE, s.last_scan DESC
            """); var rs = stmt.executeQuery()) {
            while (rs.next()) result.add(new SavedSource(rs.getString(1), rs.getString(2),
                ScanService.Mode.valueOf(rs.getString(3))));
        }
        return result;
    }

    synchronized void saveSource(String source, String category, ScanService.Mode mode) throws SQLException {
        rememberSource(source);
        update("""
            INSERT INTO source_profiles VALUES (?,?,?)
            ON CONFLICT(source_url) DO UPDATE SET category=excluded.category, mode=excluded.mode
            """, source, category, mode.name());
    }

    synchronized void saveMode(String source, ScanService.Mode mode) throws SQLException {
        rememberSource(source);
        update("""
            INSERT INTO source_profiles VALUES (?,'',?)
            ON CONFLICT(source_url) DO UPDATE SET mode=excluded.mode
            """, source, mode.name());
    }

    record Checkpoint(List<String> pending, List<String> visited) {}

    synchronized Checkpoint checkpoint(String source) throws SQLException {
        return new Checkpoint(strings("SELECT url FROM crawl_checkpoint WHERE source=? AND position>=0 ORDER BY position", source),
            strings("SELECT url FROM crawl_checkpoint WHERE source=? AND position<0", source));
    }

    synchronized void checkpoint(String source, Collection<String> urls, Collection<String> visited) throws SQLException {
        connection.setAutoCommit(false);
        try {
            update("DELETE FROM crawl_checkpoint WHERE source=?", source);
            try (var stmt = statement("INSERT INTO crawl_checkpoint VALUES (?,?,?)")) {
                int position = 0;
                for (String url : urls) {
                    stmt.setString(1, source);
                    stmt.setString(2, url);
                    stmt.setInt(3, position++);
                    stmt.addBatch();
                }
                position = -1;
                for (String url : visited) {
                    stmt.setString(1, source);
                    stmt.setString(2, url);
                    stmt.setInt(3, position--);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private List<String> strings(String sql, Object... arguments) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var stmt = statement(sql, arguments); var rs = stmt.executeQuery()) {
            while (rs.next()) result.add(rs.getString(1));
        }
        return result;
    }

    public synchronized boolean record(String source, MediaItem item) throws SQLException {
        String now = Instant.now().toString();
        boolean legacy = !strings("SELECT media_url FROM discovered WHERE source_url=? AND media_type=? AND media_url=?",
            source, item.type(), item.url()).isEmpty();
        int inserted = update("""
            INSERT OR IGNORE INTO assets
            SELECT ?,?,?,?,?,?,?,?,?,COALESCE(d.first_seen,?),?,COALESCE(d.downloaded,0)
            FROM (SELECT 1) LEFT JOIN discovered d ON d.source_url=? AND d.media_type=? AND d.media_url=?
            """, source, item.type(), item.identity(), item.postUrl(), item.url(), item.postId(), item.publishedAt(),
            item.caption(), item.assetId(), now, now, source, item.type(), item.url());
        if (inserted > 0) return !legacy;
        update("""
            UPDATE assets SET post_url=?, url=?,
              published_at=COALESCE(NULLIF(?,''),published_at), caption=COALESCE(NULLIF(?,''),caption), last_seen=?
            WHERE source=? AND type=? AND identity=?
            """, item.postUrl(), item.url(), item.publishedAt(), item.caption(), now, source, item.type(), item.identity());
        return false;
    }

    public synchronized boolean downloaded(String source, MediaItem item) throws SQLException {
        try (var stmt = statement("""
            SELECT downloaded FROM assets WHERE source=? AND type=? AND identity=?
            UNION ALL SELECT downloaded FROM discovered WHERE source_url=? AND media_type=? AND media_url=?
            """, source, item.type(), item.identity(), source, item.type(), item.url());
             var rs = stmt.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    public synchronized void markDownloaded(String source, MediaItem item) throws SQLException {
        update("UPDATE assets SET downloaded=1 WHERE source=? AND type=? AND identity=?",
            source, item.type(), item.identity());
    }

    public synchronized List<MediaItem> history(String source) throws SQLException {
        return historyEntries(source).stream().map(HistoryEntry::item).toList();
    }

    record HistoryEntry(MediaItem item, boolean downloaded, String firstSeen, String lastSeen) {}

    synchronized List<HistoryEntry> historyEntries(String source) throws SQLException {
        List<HistoryEntry> items = new ArrayList<>();
        try (var stmt = statement("""
            SELECT post_url,url,type,post_id,published_at,caption,asset_id,downloaded,first_seen,last_seen FROM assets WHERE source=?
            UNION ALL
            SELECT post_url,media_url,media_type,'','','','',downloaded,first_seen,last_seen FROM discovered d WHERE source_url=? AND NOT EXISTS
                (SELECT 1 FROM assets a WHERE a.source=d.source_url AND a.type=d.media_type AND a.url=d.media_url)
            ORDER BY first_seen
            """, source, source); var rs = stmt.executeQuery()) {
            while (rs.next()) items.add(new HistoryEntry(item(rs), rs.getBoolean(8), rs.getString(9), rs.getString(10)));
        }
        return items;
    }

    record Transfer(String source, MediaItem item, Path folder, TaskOptions options) {}

    synchronized void enqueue(Transfer job) throws SQLException {
        update("""
            INSERT INTO transfers VALUES (?,?,?,?,?,?,?) ON CONFLICT(source,type,identity,folder)
            DO UPDATE SET cookies=excluded.cookies, proxy=excluded.proxy, rules=excluded.rules
            WHERE excluded.cookies IS NOT NULL OR transfers.cookies IS NULL OR transfers.cookies NOT LIKE 'paste:%'
            """, job.source, job.item.type(), job.item.identity(), absolute(job.folder),
            job.options.cookies() == null ? null : job.options.cookies().reference(), job.options.proxy(), absolute(job.options.rules()));
    }

    synchronized List<Transfer> pending(String source, Path folder) throws SQLException {
        return pending(source, folder, Map.of());
    }

    synchronized List<Transfer> pending(String source, Path folder, Map<String, CookieInput> pasted) throws SQLException {
        List<Transfer> jobs = new ArrayList<>();
        try (var stmt = statement("""
            SELECT a.post_url,a.url,a.type,a.post_id,a.published_at,a.caption,a.asset_id,
                   t.source,t.folder,t.cookies,t.proxy,t.rules
            FROM transfers t JOIN assets a USING(source,type,identity)
            WHERE (? IS NULL OR t.source=?) AND (? IS NULL OR t.folder=?) ORDER BY t.rowid
            """, source, source, absolute(folder), absolute(folder)); var rs = stmt.executeQuery()) {
            while (rs.next()) jobs.add(new Transfer(rs.getString(8), item(rs), Path.of(rs.getString(9)),
                new TaskOptions(CookieInput.restore(rs.getString(10), pasted), rs.getString(11), path(rs.getString(12)))));
        }
        return jobs;
    }

    synchronized void completed(Transfer job) throws SQLException {
        // The final file is already in place. A retry can recognize it if interrupted here.
        markDownloaded(job.source, job.item);
        update("DELETE FROM transfers WHERE source=? AND type=? AND identity=? AND folder=?",
            job.source, job.item.type(), job.item.identity(), absolute(job.folder));
    }

    private static MediaItem item(ResultSet rs) throws SQLException {
        return new MediaItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7));
    }

    private static Path path(String value) { return value == null ? null : Path.of(value); }
    private static String absolute(Path value) { return value == null ? null : value.toAbsolutePath().normalize().toString(); }

    @Override public void close() throws SQLException { connection.close(); }
}
