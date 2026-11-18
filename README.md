# Media Scraper Full

A local JavaFX desktop app for finding, downloading, and archiving public or otherwise authorized media.

## Windows

Download **MediaScraperFull-Windows-installer** from a successful [Windows build](https://github.com/unnyanr11/media-scraper-full/actions/workflows/windows-build.yml), extract the ZIP, and open the MSI. The Windows x64 package includes Java and its Playwright driver. It uses Microsoft Edge already installed on Windows for page scans and browser sign-in. Direct downloads work without launching Edge. **MediaScraperFull-Windows-portable** is available from the same build.

## Desktop screens

| Screen | What you can do |
| --- | --- |
| Scan | Scan a site, RSS/Atom feed, open directory, or direct URL. Pick media types, a crawl limit, URL and extension filters, and an output folder. Paste or import cookies, or start an interactive browser sign-in and capture its session. |
| Batches | Import clipboard, TXT, CSV or dropped URLs and apply an import filter. Generate numbered links such as `https://example.org/image{001..100}.jpg`. Organize saved sources into categories, sort the table, and update one source, the current category, or all batches. |
| Results | Filter and sort results, queue selected files, load history, and export TXT, CSV, JSON or a metadata-rich profile backup. |
| Downloads | View pending transfers, use up to 20 workers, and pause or resume saved work. |
| Settings | Set the crawl delay, per-host download delay, request timeout, download retries, user agent, and optional non-credential request headers. |

**Start scan** picks up a saved crawl frontier when available. **Update** starts from the source URL and reports new results, allowing newly found files to be downloaded. Servers that supply usable range and validation headers can resume partway through a file. Other servers restart that file. Select **Open directory** for directory listings with extensionless entries. Choose **RSS / Atom / podcast** for a feed with enclosure links. Select **Direct file URL** for files without a recognizable extension.

The browser and downloader support a per-task HTTP proxy in the form `http://host:port`. Leave it empty for a direct connection. Click **Paste cookies…** and paste the full tab-separated Netscape text, including any header or comment lines, or select a `cookies.txt` file. Both inputs work as scan defaults or for an individual queued URL under **Configure queued tasks**. Browser contexts are isolated. Pasted cookies stay in memory while the app is open. To resume their pending downloads after restarting, paste fresh cookies, select the same source and output folder, enable downloads, and run the scan again. For file-based cookies, keep the file in the same location. Cookie values stay out of SQLite and exports.

Scan history, crawl checkpoints, and pending transfers are stored in `~/.media-scraper-full/history.sqlite`. A signed media URL that expires can be rediscovered from its source post when the extractor can match it to the same asset. Access restrictions and rate limits can leave work pending.

## Extraction and backups

| Source | Extraction |
| --- | --- |
| Instagram | Posts and reels, visible carousel controls, and image/video candidates exposed in structured page data |
| X / Twitter | Post images at original size, and direct video variants when exposed |
| Pinterest | Pin images, including original-size CDN paths when applicable |
| Reddit | Post galleries, original image hosts, and exposed direct videos |
| YouTube | Video/post links, metadata, thumbnails, and progressive video URLs when directly published in the page |
| Imgur | Gallery and album media exposed in the page |
| DeviantArt | Deviation links, original linked images, and available page media |
| Tumblr | Post links, structured metadata and media exposed in the page |
| ArtStation | Project links and page media |
| Pexels | Photo and video pages and exposed media variants |
| Mastodon | Public profile pagination and original attachments when the instance exposes its API, with DOM extraction on supported pages |
| Bluesky | Public author-feed pagination, post metadata, and full-size image attachments through AppView |
| Google Images, Bing Images, Yandex Images | Original images when a search result exposes the source URL |
| Generic pages | Largest advertised image variant, lazy-loaded media, direct file links, and bounded same-site crawling |

Platform coverage depends on what each site exposes to the selected session and within the configured crawl limit. The downloader handles unencrypted on-demand HLS with multiplexed MPEG-TS or fragmented MP4 segments and resumes using completed segments. Live or encrypted HLS, separate audio renditions, byte-range playlists, DASH, and ciphered YouTube formats are unsupported. Results can vary with site changes and access restrictions.

**Export source backup** writes JSON with observed posts and assets, IDs, available timestamps and captions, file URLs, and download status. The regular CSV and JSON exports operate on the result table.

For a forum thread or another multi-page source, enable **Archive pages**. Open the generated `archive/<source>/index.html` in the output folder. Snapshots preserve readable content and link to other archived pages and locally downloaded attachments where available. Use **Download discovered files** if you want the attachments available offline too.

### Custom extraction scripts

**Load site rules** accepts a plain-text script with CSS selectors, URL attributes, and optional URL filters. These commands read the current page's DOM. They do not run shell commands or arbitrary JavaScript.

```text
# Extract original images from a gallery's data attribute.
extract=image || .gallery img[data-full] || data-full
extract=video || .player video || data-video
extract=file || a.attachment || href
extract=page || a.pagination-next || href
exclude=/thumbnails/
```

Use `include=<Java regex>` to allow matching result URLs and `exclude=<Java regex>` to reject matching results. Page traversal can still follow pagination when an output filter excludes the page URL.

## Build from source

Use JDK 21 and Maven 3.9+.

```bash
mvn clean verify
mvn javafx:run
```

Install Chromium via Playwright if it is unavailable:

```bash
mvn dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "$(cat target/classpath.txt)" com.microsoft.playwright.CLI install chromium
```

PowerShell uses `java -cp (Get-Content target/classpath.txt -Raw) com.microsoft.playwright.CLI install chromium`.

To run the browser extraction and archive tests with an existing Chromium binary:

```bash
mvn -Dmedia.chromium.path=/path/to/chromium verify
```
