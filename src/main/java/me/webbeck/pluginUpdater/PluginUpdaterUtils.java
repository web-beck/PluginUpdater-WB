package me.webbeck.pluginUpdater;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PluginUpdaterUtils {
    public static String cleanVersion(String v) {
        if (v == null) return "";
        v = v.toLowerCase();
        if (v.startsWith("v")) v = v.substring(1);
        int metaIndex = v.indexOf('+');
        if (metaIndex != -1) v = v.substring(0, metaIndex);
        return v.replaceAll("-(paper|spigot|bukkit|purpur|folia|release|beta|alpha)", "");
    }

    public static boolean versionsMatch(String curr, String newV) {
        String c = cleanVersion(curr);
        String n = cleanVersion(newV);
        if (!c.equals(n)) {
            if (c.startsWith(n + ".") || c.startsWith(n + "-") || c.startsWith(n + "_")) return true;
            if (n.startsWith(c + ".") || n.startsWith(c + "-") || n.startsWith(c + "_")) return true;
            return false;
        }
        // Base versions match — compare +build metadata (e.g. 5.9.2-SNAPSHOT vs 5.9.2-SNAPSHOT+1003)
        String currMeta = extractMeta(curr);
        String newMeta = extractMeta(newV);
        if (newMeta == null) return true;
        if (currMeta == null) return false;
        try {
            return Integer.parseInt(currMeta) >= Integer.parseInt(newMeta);
        } catch (NumberFormatException e) {
            return currMeta.equalsIgnoreCase(newMeta);
        }
    }

    private static String extractMeta(String v) {
        if (v == null) return null;
        int i = v.indexOf('+');
        return i == -1 ? null : v.substring(i + 1);
    }

    /**
     * Splits a name into lowercase words by CamelCase boundaries, spaces, hyphens, and underscores.
     * "EssentialsX Spawn" → ["essentials", "x", "spawn"]
     * "SimpleDeathChest"  → ["simple", "death", "chest"]
     * "essentials-spawn"  → ["essentials", "spawn"]
     */
    public static List<String> splitIntoWords(String name) {
        if (name == null || name.isBlank()) return Collections.emptyList();
        String spaced = name
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return Arrays.stream(spaced.split("[ \\-_]+"))
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    /**
     * Returns true if two plugin names refer to the same project.
     *
     * Tier 1 – exact (case-insensitive)
     * Tier 2 – normalized exact (strips spaces, hyphens, underscores)
     * Tier 3 – word-based: all words from the shorter name must appear in
     *           the longer name (exact or prefix match), and the shorter
     *           name must cover at least half the longer name's word count.
     *           Single-word queries require the word to be ≥6 chars.
     *
     * Examples that now match:
     *   "EssentialsX Spawn" ↔ "EssentialsSpawn"   (word split: essentials+spawn both present)
     *   "SimpleDeathChest"  ↔ "Simple Death Chest" (normalized)
     *   "essentials-spawn"  ↔ "EssentialsSpawn"    (normalized)
     */
    public static boolean isNameMatch(String a, String b) {
        if (a == null || b == null) return false;

        // Tier 1: exact (case-insensitive)
        if (a.equalsIgnoreCase(b)) return true;

        // Tier 2: normalized (strip spaces, hyphens, underscores)
        String normA = a.toLowerCase().replaceAll("[ \\-_]", "");
        String normB = b.toLowerCase().replaceAll("[ \\-_]", "");
        if (normA.equals(normB)) return true;

        // Tier 3: word-based fuzzy match
        List<String> wordsA = splitIntoWords(a);
        List<String> wordsB = splitIntoWords(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;

        // Orient: query = shorter word list, target = longer word list
        List<String> query  = wordsA.size() <= wordsB.size() ? wordsA : wordsB;
        List<String> target = wordsA.size() <= wordsB.size() ? wordsB : wordsA;

        // Single-word queries must be at least 6 chars to prevent short false positives
        if (query.size() == 1 && query.get(0).length() < 6) return false;

        // Every query word must appear in target (exact or prefix, prefix only for ≥5-char stem)
        for (String qw : query) {
            boolean found = false;
            for (String tw : target) {
                if (tw.equals(qw)) { found = true; break; }
                int minLen = Math.min(tw.length(), qw.length());
                if (minLen >= 5 && (tw.startsWith(qw) || qw.startsWith(tw))) { found = true; break; }
            }
            if (!found) return false;
        }

        // Coverage guard: query must account for at least half of target's words
        return query.size() * 2 >= target.size();
    }

    /**
     * Converts a CamelCase string to space-separated words.
     * "SimpleDeathChest" → "Simple Death Chest"
     */
    public static String expandCamelCase(String input) {
        if (input == null) return "";
        String spaced = input
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return spaced.trim();
    }

    /**
     * Extracts a clean plugin name from a JAR filename by stripping version numbers and platform tags.
     * "EssentialsXSpawn-2.20.1.jar" → "EssentialsXSpawn"
     * "LuckPerms-Bukkit-5.4.0.jar"  → "LuckPerms"
     * "WorldEdit-bukkit-7.2.18.jar" → "WorldEdit"
     */
    public static String extractJarBaseName(File jarFile) {
        if (jarFile == null) return null;
        String name = jarFile.getName();
        if (name.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        // Strip version: requires at least major.minor dot format (-1.2, -1.2.3, -v1.2.3, -2.11.1-DEV-r3)
        name = name.replaceAll("-v?\\d[\\d]*(\\.[\\d]+)+([.-]\\w+)*$", "");
        // Strip platform identifiers
        name = name.replaceAll("(?i)[-_](bukkit|spigot|paper|purpur|folia|bungee|velocity|sponge|forge|fabric|nms)$", "");
        return name.isBlank() ? null : name.trim();
    }

    public static String joinArgs(String[] args, int start) {
        if (args.length <= start) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) builder.append(" ");
            builder.append(args[i]);
        }
        return builder.toString();
    }

    public static String joinArgs(String[] args, int start, int end) {
        if (args.length <= start || start >= end) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < end && i < args.length; i++) {
            if (i > start) builder.append(" ");
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
