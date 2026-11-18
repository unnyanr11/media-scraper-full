# Media Scraper Full

A local-first Java 21 / JavaFX desktop application for discovering publicly accessible or otherwise authorized images, videos, and post links.

## Current capabilities

- Paste a webpage URL and launch a scan without creating an app account.
- Import a **Netscape-format `cookies.txt` file** for an individual scan; different scans can select different files.
- Uses independent Playwright browser contexts (cookies are not shared between scans).
- Scrolls incrementally to discover lazy-loaded links and media.
- Finds media from `img`, `video`, `source`, `srcset`, and common Open Graph metadata.
- Follows same-page carousel controls where available; results are best-effort, not guaranteed to cover every slide on every platform.
- Background scanning with a Stop button; export discovered links as CSV.
- Slows requests and halts on authentication challenges, rate limiting, or access errors; does not evade platform restrictions.

## Requirements

- JDK 21
- Maven 3.9+
- Playwright-compatible browser dependencies

## Run

```bash
mvn clean javafx:run
```

On first launch Playwright may need its Chromium browser installed:

```bash
mvn dependency:build-classpath -Dmdep.outputFile=classpath.txt
java -cp "$(cat classpath.txt)" com.microsoft.playwright.CLI install chromium
```

For Windows PowerShell, replace the second command with:

```powershell
java -cp (Get-Content classpath.txt -Raw) com.microsoft.playwright.CLI install chromium
```

## Cookie handling

Select **Browse cookies.txt** for each scan. The input must use the Netscape cookie-jar format (`# Netscape HTTP Cookie File`, or tab-separated cookie rows). `#HttpOnly_` prefix is supported. Expired entries are ignored. The app does **not** upload, commit, or save cookie values. The file is read locally for that scan only. Never commit cookie files to source control.

Use only cookies for accounts that you own or are permitted to access; respecting access rules and rate limits remains your responsibility. Cookies do not guarantee account safety or access.

## Limitations

The initial implementation is a working general-purpose discovery baseline, **not** a complete, site-specific downloader. Some JavaScript-heavy carousels, feeds, videos, protected streams and private posts are not accessible; the tool won't bypass DRM, paywalls, or anti-bot systems. Add site-specific adapters and download management as separate modules.

## Project structure

- `src/main/java/.../MainApp.java` – JavaFX UI and CSV export.
- `src/main/java/.../ScanService.java` – background browser scan, scrolling and discovery.
- `src/main/java/.../NetscapeCookies.java` – Netscape cookie parser.
- `src/main/java/.../MediaItem.java` – discovered-item data model.
- `src/test/java/.../NetscapeCookiesTest.java` – cookie format tests.

## Persistent links and incremental updates

The app stores discovered links in a SQLite database at `~/.media-scraper-full/history.sqlite` (on Windows: `%USERPROFILE%\\.media-scraper-full\\history.sqlite`). No cookie values are stored in this database.

- **Start scan** discovers accessible links and stores them for the supplied source URL.
- **Load saved links** loads the complete stored history for that exact source URL.
- **Update (new links)** rescans that source and displays only URLs not already in the database. If Download All is enabled, only newly discovered downloadable media are fetched; previously marked downloads are not repeated.
- Links are retained after application restart. Records contain the source URL, media URL, type, post URL, first/last seen timestamps, and download-success flag.

**Important:** Update uses fresh page scanning and URL deduplication; it is not yet a platform-specific feed cursor. It cannot guarantee detection of posts skipped by infinite-scroll limits, deleted posts, or platforms that hide content from the selected session. Use the *exact same source URL* when updating. Stable media filenames minimize overwrite risk.
