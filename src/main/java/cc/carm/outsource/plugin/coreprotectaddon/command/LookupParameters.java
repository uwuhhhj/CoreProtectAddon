package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupAction;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupOptions;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.Material;

/** Query-only command surface. Unsupported parameters are rejected, never silently dropped. */
public final class LookupParameters {
    public static final List<String> COMMANDS = List.of("help", "lookup", "l", "page", "items", "debug", "reload", "near");
    public static final List<String> ACTIONS = List.of("block", "+block", "-block", "click", "kill", "container",
            "+container", "-container", "chat", "command", "inventory", "+inventory", "-inventory",
            "item", "+item", "-item", "sign", "session", "+session", "-session", "username");
    public static final Map<String, String> KEYS;
    public static final Set<String> MATERIAL_TAGS = Set.of("#button","#container","#door","#natural","#pressure_plate","#shulker_box");
    public static final List<String> FLAGS = List.of("#count", "#sum", "#verbose", "#silent");
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
        aliases(keys, "location", "location", "loc", "coord", "coords", "coordinate", "coordinates", "position");
        aliases(keys, "x", "x"); aliases(keys, "y", "y"); aliases(keys, "z", "z");
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
        return parse(args, value -> {
            Material type = Material.matchMaterial(value);
            return type != null && type.isItem() && !type.isBlock();
        });
    }

    static Parsed parse(String[] args, Predicate<String> itemOnly) {
        if (args.length == 0) throw error("MISSING_PARAMETERS", "用法：/coq l a:item t:1d i:iron_ingot");
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("l", "lookup", "page", "near").contains(command)) throw error("UNKNOWN_COMMAND", "未知子指令，请使用 /coq help。");
        if (!command.equals("near") && args.length == 2 && args[1].matches("(?i)(?:page:)?[0-9]+(?::[0-9]+)?")) {
            int[] pagination = page(args[1].replaceFirst("(?i)^page:", ""));
            return new Parsed(null, pagination[0], pagination[1] == 0 ? null : pagination[1]);
        }
        if (command.equals("page")) throw error("INVALID_PAGE", "用法：/coq page <页码>。");
        Map<String, String> values = read(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (command.equals("near")) values.putIfAbsent("radius", "5x5");
        if (values.isEmpty()) throw error("MISSING_PARAMETERS", "请提供查询参数，例如 /coq l a:item t:1d。");
        List<String> include = csv(values.get("include"));
        List<String> exclude = csv(values.get("exclude"));
        for (String value : include) if (!MATERIAL_TAGS.contains(value.toLowerCase(Locale.ROOT))) checkMaterial(value);
        for (String value : exclude) if (!value.startsWith("#")) checkMaterial(value);
        // A positive include consisting only of non-block items is unambiguous.
        // Never infer from exclusions, or silently narrow a mixed block/item query.
        String rawAction = values.get("action");
        if (rawAction == null) rawAction = !include.isEmpty() && include.stream().noneMatch(v -> v.startsWith("#")) && include.stream()
                .map(LookupParameters::material).allMatch(itemOnly) ? "item" : "all";
        LookupAction action = LookupAction.byId(rawAction);
        if (action == null) throw unsupported("a:" + rawAction);
        if (!action.hasMaterials() && !include.isEmpty()) throw error("INCOMPATIBLE_PARAMETER", "i: 类型条件不能用于 a:" + action.id() + "。");
        if (action.isItem() && values.containsKey("content"))
            cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.parse(values.get("content"));
        List<String> users = csv(values.get("user"));
        if (users.size() == 1 && users.get(0).equalsIgnoreCase("#global")) users = List.of();
        if (users.stream().anyMatch(user -> user.equalsIgnoreCase("#global")))
            throw error("INVALID_PARAMETER", "u:#global 不能与其他用户混用。");
        if (action.inventory() && users.isEmpty()) throw error("MISSING_USER", "inventory 查询必须提供 u:<玩家>。");
        if (values.containsKey("content") && !action.isItem() && !action.messages())
            throw error("INCOMPATIBLE_PARAMETER", "content: 支持 chat/command/sign 正则及 item/container 组件筛选。");
        String coordinates = values.get("location");
        if (values.containsKey("x") || values.containsKey("y") || values.containsKey("z")) {
            if (coordinates != null || !values.containsKey("x") || !values.containsKey("z"))
                throw error("INVALID_LOCATION", "坐标请使用 coord:x,y,z，或同时提供 x: 与 z:（可选 y:）。");
            coordinates = values.get("x") + "," + (values.containsKey("y") ? values.get("y") + "," : "") + values.get("z");
        }
        LookupOptions options = new LookupOptions(values.get("radius"),values.get("world"),coordinates,null,
                values.containsKey("count"), "true".equals(values.get("verbose")));
        SpatialResolver.validate(options);
        if (action == LookupAction.USERNAME && options.unresolved())
            throw error("INCOMPATIBLE_PARAMETER", "username 记录没有位置，不能按世界或范围筛选。");
        int[] pagination = page(values.getOrDefault("page", "1"));
        Integer rows = pagination[1] == 0 ? null : Integer.valueOf(pagination[1]);
        if (values.containsKey("rows")) rows = positive(values.get("rows"));
        if (pagination[1] != 0 && values.containsKey("rows")) throw error("INVALID_PAGE", "请只指定一次每页条数。");
        return new Parsed(new LookupRequest(action, users, values.get("time"), pagination[0], rows,
                include, exclude, values.get("content"),options), pagination[0], rows);
    }

    static Map<String, String> read(String input) {
        Map<String, String> values = new LinkedHashMap<>();
        int i = 0;
        while (i < input.length()) {
            while (i < input.length() && Character.isWhitespace(input.charAt(i))) i++;
            if (i == input.length()) break;
            String token = input.substring(i).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (token.startsWith("#") || token.equals("count") || token.equals("sum")) {
                String flag = token.startsWith("#") ? token.substring(1) : token;
                switch (flag) {
                    case "count", "sum" -> values.put("count", "true");
                    case "verbose", "v" -> values.put("verbose", "true");
                    case "silent" -> values.put("verbose", "false");
                    default -> throw unsupported(token);
                }
                i += token.length(); continue;
            }
            int colon = input.indexOf(':', i);
            if (colon < 0) throw error("INVALID_PARAMETER", "参数必须使用 key:value 格式。");
            String rawKey = input.substring(i, colon).toLowerCase(Locale.ROOT);
            String key = KEYS.get(rawKey);
            if (key == null) throw error("INVALID_PARAMETER", "未知参数：" + rawKey);
            i = colon + 1;
            while (i < input.length() && Character.isWhitespace(input.charAt(i))) i++;
            StringBuilder value = new StringBuilder();
            // Accept name=value and "completed:name"=value, including structured values with spaces.
            var assignment = java.util.regex.Pattern.compile("(?:[a-zA-Z0-9_.:/-]+|\"[a-zA-Z0-9_.:/-]+\"|'[a-zA-Z0-9_.:/-]+')\\s*=\\s*")
                    .matcher(input).region(i,input.length());
            if (key.equals("content") && assignment.lookingAt()) {
                int end = componentValueEnd(input,assignment.end());
                value.append(input,i,end); i = end;
                if (values.putIfAbsent(key,value.toString()) != null) throw error("INVALID_PARAMETER","参数重复：" + key);
                continue;
            }
            char quote = i < input.length() && (input.charAt(i) == '\'' || input.charAt(i) == '"') ? input.charAt(i++) : 0;
            boolean closed = quote == 0;
            int bracket = input.indexOf('[',i);
            boolean itemSyntax = bracket >= i && input.substring(i,bracket).matches("(?:[a-zA-Z0-9_.-]+:)?[a-zA-Z0-9_./-]*");
            if (quote == 0 && key.equals("content") && i < input.length() && (input.charAt(i) == '{' || itemSyntax)) {
                int end = compoundEnd(input,itemSyntax ? bracket : i);
                value.append(input,i,end); i = end;
            } else while (i < input.length()) {
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

    private static int compoundEnd(String input,int start) {
        Deque<Character> brackets = new ArrayDeque<>();
        char quote = 0;
        for (int i = start; i < input.length(); i++) {
            if (i-start > cc.carm.outsource.plugin.coreprotectaddon.service.ItemComponents.MAX_CONTENT)
                throw error("INVALID_COMPONENT_CONTENT","组件条件过长。");
            char c = input.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{' || c == '[') {
                brackets.push(c);
                if (brackets.size() > 64) throw error("INVALID_COMPONENT_CONTENT","组件条件嵌套过深。");
            } else if (c == '}' || c == ']') {
                if (brackets.isEmpty() || brackets.pop() != (c == '}' ? '{' : '['))
                    throw error("INVALID_COMPONENT_CONTENT","组件条件括号不匹配。");
                if (brackets.isEmpty()) return i+1;
            }
        }
        throw error("INVALID_COMPONENT_CONTENT","组件条件括号或引号未闭合。");
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

    private static int componentValueEnd(String input,int start) {
        if (start == input.length()) throw error("INVALID_COMPONENT_CONTENT","等号后需要填写组件值。");
        char first = input.charAt(start);
        int end = start;
        if (first == '{' || first == '[') end = compoundEnd(input,start);
        else if (first == '\'' || first == '"') {
            end++;
            boolean closed = false;
            while (end < input.length()) {
                char c = input.charAt(end++);
                if (c == '\\') { if (end < input.length()) end++; }
                else if (c == first) { closed = true; break; }
            }
            if (!closed) throw error("INVALID_COMPONENT_CONTENT","组件值引号未闭合。");
        } else while (end < input.length() && !Character.isWhitespace(input.charAt(end))) end++;
        if (end < input.length() && !Character.isWhitespace(input.charAt(end)))
            throw error("INVALID_COMPONENT_CONTENT","组件值后请用空格分隔其他查询参数。");
        return end;
    }

    /** Expands CoreProtect's public block groups on the command thread only. */
    static LookupRequest resolveTags(LookupRequest request) {
        if (java.util.stream.Stream.concat(request.include().stream(),request.exclude().stream())
                .noneMatch(value -> MATERIAL_TAGS.contains(value.toLowerCase(Locale.ROOT)))) return request;
        try {
            Map<String,Set<Material>> tags=net.coreprotect.command.parser.MaterialParser.getTags();
            java.util.function.Function<List<String>,List<String>> expand = values -> values.stream().flatMap(value -> {
                Set<Material> tag=tags.get(value.toLowerCase(Locale.ROOT));
                return tag==null ? java.util.stream.Stream.of(value) : tag.stream().map(m -> m.getKey().toString());
            }).distinct().sorted().toList();
            return new LookupRequest(request.action(),request.users(),request.time(),request.page(),request.pageSize(),
                    expand.apply(request.include()),expand.apply(request.exclude()),request.content(),request.options());
        } catch (LinkageError ex) { throw error("TAG_UNAVAILABLE","当前 CoreProtect 不提供兼容的方块标签，请使用具体类型。"); }
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
    private static QueryException components() { return error("UNSUPPORTED_COMPONENTS", "i:/e: 仅填写物品类型；组件条件请粘贴到 content: 后。"); }
    public static QueryException unsupported(String value) { return error("UNSUPPORTED_QUERY", "本版本尚未接通：" + value + "。"); }
    private static QueryException error(String code, String message) { return new QueryException(code, message); }
}
