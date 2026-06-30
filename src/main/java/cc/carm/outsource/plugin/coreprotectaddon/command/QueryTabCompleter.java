package cc.carm.outsource.plugin.coreprotectaddon.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class QueryTabCompleter implements TabCompleter {

    private static final String PERMISSION = "coreprotectaddon.command.query";
    private static final List<String> SUBCOMMANDS = List.of("chat", "command");
    private static final List<String> PARAM_KEYS = List.of("user:", "time:", "page:", "content:");
    private static final List<String> TIME_SUGGESTIONS = List.of(
            "time:1h", "time:1d", "time:7d", "time:10d-30d"
    );
    private static final List<String> PAGE_SUGGESTIONS = List.of("page:1");
    private static final Set<String> VALID_SUBCOMMANDS = Set.of("chat", "command");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }

        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return filterByPrefix(SUBCOMMANDS, input);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!VALID_SUBCOMMANDS.contains(subcommand)) {
            return List.of();
        }

        String current = args[args.length - 1];
        String lowerCurrent = current.toLowerCase(Locale.ROOT);

        if (lowerCurrent.startsWith("time:")) {
            return filterByPrefix(TIME_SUGGESTIONS, lowerCurrent);
        }
        if (lowerCurrent.startsWith("page:")) {
            return filterByPrefix(PAGE_SUGGESTIONS, lowerCurrent);
        }

        Set<String> usedKeys = CommandParameter.readKeys(args, 1, args.length - 1);
        List<String> candidates = new ArrayList<>();
        for (String key : PARAM_KEYS) {
            String normalized = key.substring(0, key.length() - 1);
            if (!usedKeys.contains(normalized) || lowerCurrent.startsWith(key)) {
                candidates.add(key);
            }
        }

        return filterByPrefix(candidates, lowerCurrent);
    }

    private static List<String> filterByPrefix(List<String> values, String input) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        Set<String> matches = new LinkedHashSet<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerInput)) {
                matches.add(value);
            }
        }
        return new ArrayList<>(matches);
    }

}
