package me.webbeck.pluginUpdater;

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
