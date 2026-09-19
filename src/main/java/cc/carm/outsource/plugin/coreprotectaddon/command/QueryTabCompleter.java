package cc.carm.outsource.plugin.coreprotectaddon.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import java.util.*;

public final class QueryTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) return filter(LookupParameters.COMMANDS.stream().filter(value ->
                sender.hasPermission("coreprotectaddon.command." + (value.equals("reload") || value.equals("debug") ? value : "query")))
                .toList(),args.length == 0 ? "" : args[0]);
        if (args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("coreprotectaddon.command.debug")) return List.of();
            boolean manage = sender.hasPermission("coreprotectaddon.command.reload");
            if (args.length > 2 && args[1].equalsIgnoreCase("whitelist") && !manage) return List.of();
            List<String> components = PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy();
            if (args.length == 4 && args[1].equalsIgnoreCase("whitelist") && args[2].equalsIgnoreCase("add")
                    && sender instanceof org.bukkit.entity.Player player)
                components = player.getInventory().getItemInMainHand().getDataTypes().stream().map(t -> t.getKey().toString()).sorted().toList();
            return complete(args,List.of(),List.of(),List.of(),components).stream()
                    .filter(value -> manage || !value.equals("whitelist")).toList();
        }
        if (!sender.hasPermission("coreprotectaddon.command.query")) return List.of();
        return complete(args, Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).sorted().toList(),
                Arrays.stream(Material.values()).filter(m -> !m.isLegacy()).map(m -> m.name().toLowerCase(Locale.ROOT)).sorted().toList(),
                Bukkit.getWorlds().stream().map(w -> w.getName()).toList(),PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy()).stream()
                .filter(value -> !value.equals("reload") || sender.hasPermission("coreprotectaddon.command.reload")).toList();
    }
    /** Pure completion logic for regression tests; completion never queries the database. */
    public static List<String> complete(String[] args, List<String> players, List<String> materials, List<String> worlds) {
        return complete(args,players,materials,worlds,List.of());
    }
    public static List<String> complete(String[] args, List<String> players, List<String> materials, List<String> worlds, List<String> components) {
        String input = args.length == 0 ? "" : args[args.length - 1];
        if (args.length <= 1) return filter(LookupParameters.COMMANDS, input);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("debug")) {
            if (args.length == 2) return filter(List.of("item","whitelist"),input);
            if (args[1].equalsIgnoreCase("whitelist")) {
                if (args.length == 3) return filter(List.of("add","remove"),input);
                if (args.length == 4 && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))) return filter(components,input);
            }
            return args.length == 3 && args[1].equalsIgnoreCase("item") ? filter(List.of("1","2","3"),input) : List.of();
        }
        if (root.equals("help")) return args.length == 2 ? filter(List.of("1", "2", "3"),input) : List.of();
        if (root.equals("page")) return args.length == 2 ? filter(List.of("1", "2", "3", "1:15", "2:15"), input) : List.of();
        if (root.equals("items")) return args.length == 2 ? filter(List.of("1", "2", "3", "1:15", "1:45"), input) : List.of();
        if (!root.equals("l") && !root.equals("lookup") && !root.equals("near")) return List.of();
        // The native parser also accepts a: item and comma lists split across command arguments.
        if (args.length > 2) {
            String previous = args[args.length - 2];
            int separator = previous.indexOf(':');
            if (separator > 0 && (input.indexOf(':') < 0 || "content".equals(LookupParameters.KEYS.get(previous.substring(0,separator).toLowerCase(Locale.ROOT))))
                    && (previous.endsWith(":") || previous.endsWith(","))
                    && LookupParameters.KEYS.containsKey(previous.substring(0, separator).toLowerCase(Locale.ROOT))) {
                String[] combined = Arrays.copyOf(args, args.length - 1);
                combined[combined.length - 1] = previous + input;
                return complete(combined, players, materials, worlds, components).stream().map(s -> s.substring(previous.length())).toList();
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
            if (key.equals("content")) {
                // Message queries retain their regex input; do not suggest component names there.
                for (int i=1;i<args.length-1;i++) {
                    int split = args[i].indexOf(':');
                    if (split > 0 && "action".equals(LookupParameters.KEYS.get(args[i].substring(0,split).toLowerCase(Locale.ROOT)))) {
                        String action = args[i].substring(split+1);
                        if (action.isEmpty() && i+1 < args.length-1) action = args[i+1];
                        var type = cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupAction.byId(action);
                        if (type != null && !type.isItem()) return List.of();
                    }
                }
                String value = input.substring(colon+1);
                String quote = value.startsWith("\"") ? "\"" : value.startsWith("'") ? "'" : "";
                String start = prefix + quote;
                String typed = value.substring(quote.length());
                boolean shorthand = !typed.isEmpty() && !typed.contains(":");
                return filter(components.stream().map(String::trim)
                        .filter(s -> s.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))
                        .map(s -> s.contains(":") ? s : "minecraft:" + s)
                        .map(s -> shorthand && s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s)
                        .map(s -> start + s + quote).toList(),input);
            }
            List<String> values = switch (key) {
                case "action" -> LookupParameters.ACTIONS;
                case "user" -> players;
                case "include", "exclude" -> materials;
                case "time" -> timeValues(input.substring(colon + 1));
                case "page" -> List.of("1", "2", "3", "1:15", "2:15");
                case "rows" -> List.of("15", "50", "100");
                case "radius" -> java.util.stream.Stream.concat(List.of("5", "5x5", "10x5x20", "#global", "#we", "#worldedit").stream(),worlds.stream().map(w -> "#"+w)).toList();
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
