package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Shareable site rules with a minimal, non-executable text format. */
public final class SiteRules {
    private final List<Pattern> include;
    private final List<Pattern> exclude;

    private SiteRules(List<Pattern> include, List<Pattern> exclude) {
        this.include = include;
        this.exclude = exclude;
    }

    public static SiteRules empty() { return new SiteRules(List.of(), List.of()); }

    // Lines: include=<java regex>, exclude=<java regex>
    // Not a scripting engine: configurations cannot run arbitrary code.
    public static SiteRules load(Path path) throws IOException {
        List<Pattern> inc = new ArrayList<>(), exc = new ArrayList<>();
        if (Files.size(path) > 100_000) throw new IOException("Rules file too large");
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            int equals = trimmed.indexOf('=');
            if (equals < 0) throw new IOException("Rule must use include= or exclude=");
            String name = trimmed.substring(0, equals).trim();
            Pattern pattern;
            try { pattern = Pattern.compile(trimmed.substring(equals + 1).trim(), Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException e) { throw new IOException("Invalid rule: " + line, e); }
            if (name.equals("include")) inc.add(pattern);
            else if (name.equals("exclude")) exc.add(pattern);
            else throw new IOException("Unknown rule: " + name);
            if (inc.size() + exc.size() > 50) throw new IOException("Maximum 50 rules");
        }
        return new SiteRules(List.copyOf(inc), List.copyOf(exc));
    }

    public boolean allows(String url) {
        boolean included = include.isEmpty() || include.stream().anyMatch(p -> p.matcher(url).find());
        return included && exclude.stream().noneMatch(p -> p.matcher(url).find());
    }
}
