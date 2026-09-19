package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupAction;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRequest;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import java.util.*;

/** Query-only command surface. Unsupported parameters are rejected, never silently dropped. */
public final class LookupParameters {
    public static final List<String> COMMANDS = List.of("help", "lookup", "l", "page", "near");
    public static final List<String> ACTIONS = List.of("block", "+block", "-block", "click", "kill", "container",
            "+container", "-container", "chat", "command", "inventory", "+inventory", "-inventory",
            "item", "+item", "-item", "sign", "session", "+session", "-session", "username");
    public static final Map<String, String> KEYS;
    public static final List<String> FLAGS = List.of("#count", "#sum", "#container", "#preview", "#verbose", "#silent");
    static {
        Map<String, String> keys = new LinkedHashMap<>();
        aliases(keys, "user", "u", "user", "users", "p");
        aliases(keys, "time", "t", "time");
        aliases(keys, "action", "a", "action");
        aliases(keys, "include", "i", "include", "item", "items", "b", "block", "blocks");
        aliases(keys, "exclude", "e", "exclude");
        aliases(keys, "page", "page");
        aliases(keys, "rows", "rows");
        aliases(keys, "content", "content", "c", "message", "m", "command");
        aliases(keys, "radius", "r", "radius");
        aliases(keys, "world", "w", "world");
        aliases(keys, "location", "location", "loc", "x", "y", "z");
        KEYS = Collections.unmodifiableMap(keys);
    }
    private LookupParameters() {}
    private static void aliases(Map<String, String> target, String canonical, String... aliases) {
        for (String alias : aliases) target.put(alias, canonical);
    }
    public record Parsed(LookupRequest request, Integer page, Integer rows) {
        public boolean continuation() { return request == null; }
    }

    public static Parsed parse(String[] args) {
        if (args.length == 0) throw error("MISSING_PARAMETERS", "用法：/coq l a:item t:1d i:iron_ingot");
        String command = args[0].toLowerCase(Locale.ROOT);
        if (command.equals("near")) throw unsupported("near / 半径查询");
        if (!List.of("l", "lookup", "page").contains(command)) throw error("UNKNOWN_COMMAND", "未知子指令，请使用 /coq help。");
        if (args.length == 2 && args[1].matches("(?i)(?:page:)?[0-9]+(?::[0-9]+)?")) {
            int[] pagination = page(args[1].replaceFirst("(?i)^page:", ""));
            return new Parsed(null, pagination[0], pagination[1] == 0 ? null : pagination[1]);
        }
        if (command.equals("page")) throw error("INVALID_PAGE", "用法：/coq page <页码>。");
        Map<String, String> values = read(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (values.isEmpty()) throw error("MISSING_PARAMETERS", "请提供查询参数，例如 /coq l a:item t:1d。");
        for (String key : List.of("radius", "world", "location")) if (values.containsKey(key)) throw unsupported(key);
        String rawAction = values.getOrDefault("action", "block");
        LookupAction action = LookupAction.byId(rawAction);
        if (action == null) throw unsupported("a:" + rawAction);
        List<String> include = csv(values.get("include"));
        List<String> exclude = csv(values.get("exclude"));
        for (String value : include) checkMaterial(value);
        for (String value : exclude) checkMaterial(value);
        if (!action.isItem() && (!include.isEmpty() || !exclude.isEmpty())) throw error("INCOMPATIBLE_PARAMETER", "i:/e: 物品条件不能用于 a:" + action.id() + "。");
        if (action.isItem() && values.containsKey("content")) throw error("INCOMPATIBLE_PARAMETER", "content: 仅用于 a:chat 或 a:command。");
        List<String> users = csv(values.get("user"));
        if (users.size() == 1 && users.get(0).equalsIgnoreCase("#global")) users = List.of();
        for (String user : users) if (user.startsWith("#")) throw unsupported("u:" + user);
        int[] pagination = page(values.getOrDefault("page", "1"));
        Integer rows = pagination[1] == 0 ? null : Integer.valueOf(pagination[1]);
        if (values.containsKey("rows")) rows = positive(values.get("rows"));
        if (pagination[1] != 0 && values.containsKey("rows")) throw error("INVALID_PAGE", "请只指定一次每页条数。");
        return new Parsed(new LookupRequest(action, users, values.get("time"), pagination[0], rows,
                include, exclude, values.get("content")), pagination[0], rows);
    }

    static Map<String, String> read(String input) {
        Map<String, String> values = new LinkedHashMap<>();
        int i = 0;
        while (i < input.length()) {
            while (i < input.length() && Character.isWhitespace(input.charAt(i))) i++;
            if (i == input.length()) break;
            if (input.charAt(i) == '#') throw unsupported(input.substring(i).split("\\s+", 2)[0]);
            int colon = input.indexOf(':', i);
            if (colon < 0) throw error("INVALID_PARAMETER", "参数必须使用 key:value 格式。");
            String rawKey = input.substring(i, colon).toLowerCase(Locale.ROOT);
            String key = KEYS.get(rawKey);
            if (key == null) throw error("INVALID_PARAMETER", "未知参数：" + rawKey);
            i = colon + 1;
            while (i < input.length() && Character.isWhitespace(input.charAt(i))) i++;
            StringBuilder value = new StringBuilder();
            char quote = i < input.length() && (input.charAt(i) == '\'' || input.charAt(i) == '"') ? input.charAt(i++) : 0;
            boolean closed = quote == 0;
            while (i < input.length()) {
                char c = input.charAt(i);
                if (quote != 0) {
                    if (c == quote) { i++; closed = true; break; }
                    if (c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == quote) { value.append(quote); i += 2; continue; }
                } else if (Character.isWhitespace(c)) {
                    // CoreProtect allows lists written as i:stone, dirt and i: stone.
                    if (value.toString().endsWith(",")) { i++; continue; }
                    break;
                }
                if ((key.equals("include") || key.equals("exclude")) && (c == '[' || c == '{')) throw components();
                value.append(c);
                i++;
            }
            if (!closed) throw error("INVALID_PARAMETER", "参数引号未闭合。");
            if (i < input.length() && !Character.isWhitespace(input.charAt(i))) throw error("INVALID_PARAMETER", "参数之间需要空格。");
            if (value.isEmpty()) throw error("INVALID_PARAMETER", rawKey + ": 缺少参数值。");
            if (values.putIfAbsent(key, value.toString()) != null) throw error("INVALID_PARAMETER", "参数重复：" + key);
        }
        return values;
    }

    public static List<String> csv(String value) {
        if (value == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank()) throw error("INVALID_PARAMETER", "列表中存在空值。");
            result.add(token.trim());
        }
        if (result.size() > 100) throw error("INVALID_PARAMETER", "每个列表最多 100 项。");
        return List.copyOf(result);
    }

    public static String material(String value) {
        checkMaterial(value);
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }
    private static void checkMaterial(String value) {
        if (value.indexOf('[') >= 0 || value.indexOf('{') >= 0) throw components();
        if (value.startsWith("#")) throw unsupported("物品标签 " + value);
        if (!value.matches("(?i)(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) throw error("INVALID_MATERIAL", "物品名称无效：" + value);
    }
    public static int[] page(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length > 2) throw error("INVALID_PAGE", "页码格式无效。");
        return new int[]{positive(parts[0]), parts.length == 2 ? positive(parts[1]) : 0};
    }
    private static int positive(String value) {
        try { int number = Integer.parseInt(value); if (number > 0) return number; } catch (NumberFormatException ignored) { }
        throw error("INVALID_PAGE", "页码及每页条数必须为正整数。");
    }
    private static QueryException components() { return error("UNSUPPORTED_COMPONENTS", "本版本尚不支持物品组件条件。"); }
    public static QueryException unsupported(String value) { return error("UNSUPPORTED_QUERY", "本版本尚未接通：" + value + "。"); }
    private static QueryException error(String code, String message) { return new QueryException(code, message); }
}
