package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, testable URL import and sequence expansion. */
public final class UrlSources {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("\\{(\\d+)\\.\\.(\\d+)\\}");
    private static final int MAX_URLS = 10_000;

    private UrlSources() {}

    public static List<String> fromText(String text) {
        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find() && unique.size() < MAX_URLS) {
            String candidate = matcher.group().replaceAll("[),.;]+$", "");
            try {
                URI uri = URI.create(candidate);
                if (uri.getHost() != null && (uri.getScheme().equalsIgnoreCase("http")
                    || uri.getScheme().equalsIgnoreCase("https"))) unique.add(candidate);
            } catch (IllegalArgumentException ignored) {}
        }
        return new ArrayList<>(unique);
    }

    public static List<String> fromFile(Path path) throws IOException {
        if (Files.size(path) > 10_000_000) throw new IOException("Input file exceeds 10 MB");
        return fromText(Files.readString(path));
    }

    /** Expand exactly one padded inclusive numeric range, e.g. image{001..100}.jpg. */
    public static List<String> expandSequence(String template) {
        Matcher matcher = RANGE.matcher(template);
        if (!matcher.find()) throw new IllegalArgumentException("Use {001..100} in the URL");
        int start = Integer.parseInt(matcher.group(1));
        int end = Integer.parseInt(matcher.group(2));
        if (end < start || ((long) end - start) >= MAX_URLS)
            throw new IllegalArgumentException("Range must ascend and contain at most 10,000 URLs");
        if (matcher.find()) throw new IllegalArgumentException("Only one numeric range is supported");
        matcher.reset();
        matcher.find();
        String prefix = template.substring(0, matcher.start());
        String suffix = template.substring(matcher.end());
        int width = matcher.group(1).length();
        List<String> urls = new ArrayList<>();
        for (long i = start; i <= end; i++) {
            String candidate = prefix + String.format(java.util.Locale.ROOT, "%0" + width + "d", i) + suffix;
            var parsed = fromText(candidate);
            if (parsed.size() != 1 || !candidate.equals(parsed.getFirst()))
                throw new IllegalArgumentException("Generated sequence must consist of valid http(s) URLs");
            urls.add(candidate);
        }
        return urls;
    }
}
