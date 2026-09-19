package cc.carm.outsource.plugin.coreprotectaddon.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import java.util.*;

public final class QueryTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("coreprotectaddon.command.query")) return List.of();
        return complete(args, Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).sorted().toList(),
                Arrays.stream(Material.values()).filter(m -> !m.isLegacy()).map(m -> m.name().toLowerCase(Locale.ROOT)).sorted().toList(),
                Bukkit.getWorlds().stream().map(w -> w.getName()).toList());
    }
    /** Pure completion logic for regression tests; completion never queries the database. */
    public static List<String> complete(String[] args, List<String> players, List<String> materials, List<String> worlds) {
        String input = args.length == 0 ? "" : args[args.length - 1];
        if (args.length <= 1) return filter(LookupParameters.COMMANDS, input);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("page")) return args.length == 2 ? filter(List.of("1", "2", "3", "1:15", "2:15"), input) : List.of();
        if (root.equals("near")) return filter(List.of("r:5", "r:10", "r:20"), input);
        if (!root.equals("l") && !root.equals("lookup")) return List.of();
        // The native parser also accepts a: item and comma lists split across command arguments.
        if (args.length > 2 && input.indexOf(':') < 0) {
            String previous = args[args.length - 2];
            int separator = previous.indexOf(':');
            if (separator > 0 && (previous.endsWith(":") || previous.endsWith(","))
                    && LookupParameters.KEYS.containsKey(previous.substring(0, separator).toLowerCase(Locale.ROOT))) {
                String[] combined = Arrays.copyOf(args, args.length - 1);
                combined[combined.length - 1] = previous + input;
                return complete(combined, players, materials, worlds).stream().map(s -> s.substring(previous.length())).toList();
            }
        }
        if (args.length == 2 && input.matches("[0-9]+(?::[0-9]*)?")) {
            int colon = input.indexOf(':');
            return colon >= 0 ? filter(List.of(input.substring(0, colon) + ":15", input.substring(0, colon) + ":50", input.substring(0, colon) + ":100"), input)
                    : filter(List.of("1", "2", "3", input + ":15"), input);
        }
        int colon = input.indexOf(':');
        if (colon >= 0) {
            String prefix = input.substring(0, colon + 1);
            String key = LookupParameters.KEYS.get(input.substring(0, colon).toLowerCase(Locale.ROOT));
            if (key == null) return List.of();
            List<String> values = switch (key) {
                case "action" -> LookupParameters.ACTIONS;
                case "user" -> players;
                case "include", "exclude" -> materials;
                case "time" -> timeValues(input.substring(colon + 1));
                case "page" -> List.of("1", "2", "3", "1:15", "2:15");
                case "rows" -> List.of("15", "50", "100");
                case "radius" -> List.of("5", "10", "20", "#global", "#world", "#worldedit");
                case "world" -> worlds;
                default -> List.of();
            };
            if (Set.of("user", "include", "exclude").contains(key)) {
                int comma = input.lastIndexOf(',');
                if (comma > colon) prefix = input.substring(0, comma + 1);
                String suffix = input.substring(prefix.length());
                if ((key.equals("include") || key.equals("exclude")) && suffix.startsWith("minecraft:")) prefix += "minecraft:";
            }
            String start = prefix;
            return filter(values.stream().map(v -> start + v).toList(), input);
        }
        Set<String> used = new HashSet<>();
        for (int i = 1; i < args.length - 1; i++) {
            int split = args[i].indexOf(':');
            if (split > 0) used.add(LookupParameters.KEYS.get(args[i].substring(0, split).toLowerCase(Locale.ROOT)));
        }
        List<String> options = new ArrayList<>();
        LookupParameters.KEYS.forEach((alias, key) -> { if (!used.contains(key)) options.add(alias + ":"); });
        options.addAll(LookupParameters.FLAGS);
        if (args.length == 2) options.addAll(List.of("1", "2", "1:15"));
        return filter(options, input);
    }
    private static List<String> filter(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).distinct().toList();
    }
    private static List<String> timeValues(String input) {
        if (input.matches(".*[0-9]$")) return List.of("w", "d", "h", "m", "s", "mo", "y").stream().map(unit -> input + unit).toList();
        return List.of("30m", "1h", "12h", "1d", "7d", "10d-12d");
    }
}
