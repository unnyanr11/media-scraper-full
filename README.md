# Media Scraper Full

A local JavaFX desktop app for finding, downloading, and archiving public or otherwise authorized media.

## Windows

Open the repository's [Windows build runs](https://github.com/unnyanr11/media-scraper-full/actions/workflows/windows-build.yml). From a successful run, download **MediaScraperFull-Windows-installer**, extract the artifact ZIP, and open the MSI. It includes Java and Chromium. **MediaScraperFull-Windows-portable** is also available as a self-contained app folder.

## Scan and download

1. Paste a gallery, profile, post, thread, image-search URL, or direct file URL, or import a batch from the clipboard or a TXT/CSV file. **Generate sequence** expands patterns such as `https://example.org/image{001..100}.jpg`.
2. Set **Max pages** (1–500), optional URL keyword and extension filters, and any site rules. Use **Configure queued tasks** to assign a separate session or HTTP proxy to individual URLs.
3. Choose an output folder and select **Download discovered files** for automatic downloads. The app supports 1–20 workers and spaces requests to the same host.
4. Use **Pause downloads** to leave transfers pending and **Resume downloads** to continue them, including after a restart. **Stop** also stops discovery. Servers that supply a usable validator and byte ranges can resume a partial file. Other servers start that file again.
5. **Start scan** picks up a saved crawl frontier when available. **Update (new links)** starts at the source URL and reports records new to that source. Use **Load saved links** or **Export source backup** with the original source URL.

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
| Mastodon | Public profile pagination and original attachments when the instance exposes its API, with DOM extraction on supported pages |
| Bluesky | Public author-feed pagination, post metadata, and full-size image attachments through AppView |
| Google Images, Bing Images, Yandex Images | Original images when a search result exposes the source URL |
| Generic pages | Largest advertised image variant, lazy-loaded media, direct file links, and bounded same-site crawling |

Platform coverage depends on what the site returns to the selected session and within the configured crawl limit. DRM-protected media, ciphered YouTube formats, and HLS/DASH-only streams have no download support. Results can vary with site changes, account access, and platform restrictions.

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
