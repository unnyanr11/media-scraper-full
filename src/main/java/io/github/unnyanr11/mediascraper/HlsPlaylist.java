package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.net.URI;
import java.util.*;

record HlsPlaylist(String variant, List<String> parts, String extension) {
    static HlsPlaylist parse(String text, String source) throws IOException {
        var rows = text.strip().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (rows.isEmpty() || !rows.getFirst().equals("#EXTM3U")) throw new IOException("Invalid HLS playlist");
        String variant = null;
        long highest = -1;
        boolean partNext = false;
        boolean end = false;
        boolean init = false;
        var urls = new ArrayList<String>();
        for (int i = 1; i < rows.size(); i++) {
            String line = rows.get(i);
            if (line.startsWith("#EXT-X-KEY:") && !line.substring(11).matches("METHOD=NONE(?:,.*)?")
                || line.startsWith("#EXT-X-SESSION-KEY:")) throw new IOException("Encrypted HLS is unsupported");
            if (line.startsWith("#EXT-X-BYTERANGE:") || line.startsWith("#EXT-X-DISCONTINUITY"))
                throw new IOException("This HLS segment layout isn't supported");
            if (line.startsWith("#EXT-X-STREAM-INF:") && i + 1 < rows.size()) {
                long bandwidth = Arrays.stream(line.substring(18).split(","))
                    .filter(value -> value.matches("BANDWIDTH=[0-9]{1,12}"))
                    .mapToLong(value -> Long.parseLong(value.substring(10))).findFirst().orElse(0);
                String url = MediaLists.resolve(source, rows.get(++i));
                if (url != null && !line.contains("AUDIO=") && bandwidth > highest) {
                    variant = url;
                    highest = bandwidth;
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                if (init || line.contains("BYTERANGE")) throw new IOException("This HLS initialization layout isn't supported");
                var uri = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                if (!uri.find()) throw new IOException("Invalid HLS initialization fragment");
                urls.add(requireUrl(source, uri.group(1)));
                init = true;
            } else if (line.startsWith("#EXTINF:")) {
                partNext = true;
            } else if (line.equals("#EXT-X-ENDLIST")) {
                end = true;
            } else if (!line.startsWith("#") && partNext) {
                urls.add(requireUrl(source, line));
                partNext = false;
            }
        }
        if (variant != null) return new HlsPlaylist(variant, List.of(), "");
        if (!end || urls.size() <= (init ? 1 : 0) || urls.size() > 20_000) {
            throw new IOException("Choose a finite HLS playlist with a supported media rendition");
        }
        if (!init && urls.stream().anyMatch(url -> URI.create(url).getPath().matches("(?i).*\\.(aac|mp3|vtt|mp4|m4s)$"))) {
            throw new IOException("This HLS media format isn't supported");
        }
        return new HlsPlaylist(null, List.copyOf(urls), init ? "mp4" : "ts");
    }

    private static String requireUrl(String source, String path) throws IOException {
        String url = MediaLists.resolve(source, path);
        if (url == null) throw new IOException("Invalid HLS segment URL");
        return url;
    }
}
