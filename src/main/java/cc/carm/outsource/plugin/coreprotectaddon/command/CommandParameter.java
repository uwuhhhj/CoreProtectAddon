package cc.carm.outsource.plugin.coreprotectaddon.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CommandParameter(@NotNull String[] inputs, @NotNull Map<String, String> params) {

    public static CommandParameter parse(@NotNull String[] inputs) {
        return new CommandParameter(inputs, read(inputs));
    }

    static final Pattern PARAMS_PATTERN = Pattern.compile("([\\w.-]+):(?:'([^']*)'|\"([^\"]*)\"|(\\S+))");

    public @Nullable String get(@NotNull String... keys) {
        for (String key : keys) {
            String val = this.params.get(key.toLowerCase());
            if (val != null) {
                return val;
            }
        }
        return null;
    }

    public boolean with(@NotNull Consumer<String> value, String... keys) {
        String val = get(keys);
        if (val != null) {
            value.accept(val);
            return true;
        }
        return false;
    }


    static Map<String, String> read(@NotNull String[] inputs) {
        Map<String, String> params = new HashMap<>();
        String joinedInputs = String.join(" ", inputs);
        Matcher matcher = PARAMS_PATTERN.matcher(joinedInputs);

        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            String value = matcher.group(2) != null ? matcher.group(2) :
                    (matcher.group(3) != null ? matcher.group(3) : matcher.group(4));
            params.put(key, value);
        }
        return params;
    }

    static Set<String> readKeys(@NotNull String[] inputs, int startInclusive, int endExclusive) {
        Set<String> keys = new HashSet<>();
        if (startInclusive >= endExclusive) {
            return keys;
        }

        int safeStart = Math.max(0, startInclusive);
        int safeEnd = Math.min(inputs.length, endExclusive);
        if (safeStart >= safeEnd) {
            return keys;
        }

        String[] slice = new String[safeEnd - safeStart];
        System.arraycopy(inputs, safeStart, slice, 0, slice.length);
        Matcher matcher = PARAMS_PATTERN.matcher(String.join(" ", slice));
        while (matcher.find()) {
            keys.add(matcher.group(1).toLowerCase());
        }
        return keys;
    }

    static boolean validate(@NotNull String[] inputs, @NotNull String... keys) {
        for (String key : keys) {
            for (String input : inputs) {
                if (input.equalsIgnoreCase(key)) {
                    return true;
                }
            }
        }
        return false;
    }


}
