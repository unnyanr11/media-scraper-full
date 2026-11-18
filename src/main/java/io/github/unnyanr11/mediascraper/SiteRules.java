package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Shareable site rules with a minimal, non-executable text format. */
public final class SiteRules {
    record Command(String type, String selector, String attribute) {}
    private final List<Command> commands;
    private final List<Pattern> include;
    private final List<Pattern> exclude;

    private SiteRules(List<Pattern> include, List<Pattern> exclude, List<Command> commands) {
        this.include = include;
        this.exclude = exclude;
        this.commands = commands;
    }

    public static SiteRules empty() { return new SiteRules(List.of(), List.of(), List.of()); }

    List<Map<String, String>> commands() {
        return commands.stream().map(rule -> Map.of("type", rule.type,
            "selector", rule.selector, "attribute", rule.attribute)).toList();
    }

    // Each extraction instruction selects a URL attribute from matching DOM nodes.
    public static SiteRules load(Path path) throws IOException {
        List<Pattern> inc = new ArrayList<>(), exc = new ArrayList<>();
        if (Files.size(path) > 100_000) throw new IOException("Rules file too large");
        List<Command> commands = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            int equals = trimmed.indexOf('=');
            if (equals < 0) throw new IOException("Expected an include, exclude or extract instruction");
            String name = trimmed.substring(0, equals).trim();
            String argument = trimmed.substring(equals + 1).trim();
            if (name.equals("extract")) {
                String[] fields = argument.split("\\s*\\|\\|\\s*", 3);
                if (fields.length != 3 || !Set.of("image", "video", "file", "page").contains(fields[0])
                    || fields[1].isBlank() || !fields[2].matches("[a-zA-Z_][a-zA-Z0-9_:-]*"))
                    throw new IOException("Use extract=type || CSS selector || URL attribute");
                if (commands.size() == 50) throw new IOException("Maximum 50 extraction instructions");
                commands.add(new Command(fields[0], fields[1], fields[2]));
                continue;
            }
            Pattern pattern;
            try { pattern = Pattern.compile(argument, Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException e) { throw new IOException("Invalid rule: " + line, e); }
            if (name.equals("include")) inc.add(pattern);
            else if (name.equals("exclude")) exc.add(pattern);
            else throw new IOException("Unknown rule: " + name);
            if (inc.size() + exc.size() > 50) throw new IOException("Maximum 50 rules");
        }
        return new SiteRules(List.copyOf(inc), List.copyOf(exc), List.copyOf(commands));
    }

    public boolean allows(String url) {
        boolean included = include.isEmpty() || include.stream().anyMatch(p -> p.matcher(url).find());
        return included && exclude.stream().noneMatch(p -> p.matcher(url).find());
    }
}
