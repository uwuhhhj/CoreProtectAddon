package cc.carm.outsource.plugin.coreprotectaddon.service;

import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Syntax is validated without server access; typed values are parsed on the server thread. */
public record ItemContent(String id, boolean itemOnly, Set<String> present, String compound, String valueSearch) {
    public ItemContent(String id,boolean itemOnly,Set<String> present,String compound) {
        this(id,itemOnly,present,compound,null);
    }
    public static ItemContent parse(String input) {
        if (input == null || input.isBlank() || input.length() > ItemComponents.MAX_CONTENT) throw invalid();
        String text = input.trim();
        if (text.startsWith("{")) return new ItemContent(null,false,Set.of(),text);
        // Standalone component=value is the common path after completing a component name.
        int equal = text.indexOf('=');
        int bracket = text.indexOf('[');
        if (equal >= 0 && (bracket < 0 || equal < bracket)) {
            String key = componentId(text.substring(0,equal));
            String value = text.substring(equal+1).trim();
            if (value.isEmpty()) throw invalid();
            return new ItemContent(null,false,Set.of(key),"{\"" + key + "\":" + value + "}");
        }
        if (bracket < 0) {
            String id = text.matches("(?i)(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+") ? componentId(text) : null;
            return new ItemContent(id,false,Set.of(),null,text);
        }
        String id = text.substring(0,bracket).isBlank() ? null : componentId(text.substring(0,bracket));
        if (!text.endsWith("]")) throw invalid();
        List<String> conditions = split(text.substring(bracket+1,text.length()-1));
        Set<String> present = new LinkedHashSet<>();
        List<String> exact = new ArrayList<>();
        for (String condition : conditions) {
            int separator = condition.indexOf('=');
            String key = componentId(separator < 0 ? condition : condition.substring(0,separator));
            if (!present.add(key)) throw invalid();
            if (separator >= 0) {
                String value = condition.substring(separator+1).trim();
                if (value.isEmpty()) throw invalid();
                exact.add("\"" + key + "\":" + value);
            }
        }
        return new ItemContent(id,true,Set.copyOf(present),exact.isEmpty() ? null : "{" + String.join(",",exact) + "}");
    }

    private static List<String> split(String text) {
        if (text.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        Deque<Character> brackets = new ArrayDeque<>();
        char quote = 0;
        int start = 0;
        for (int i=0;i<text.length();i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{' || c == '[') {
                brackets.push(c);
                if (brackets.size() > 64) throw invalid();
            } else if (c == '}' || c == ']') {
                if (brackets.isEmpty() || brackets.pop() != (c == '}' ? '{' : '[')) throw invalid();
            } else if (c == ',' && brackets.isEmpty()) {
                parts.add(text.substring(start,i).trim()); start = i+1;
            }
        }
        if (quote != 0 || !brackets.isEmpty()) throw invalid();
        parts.add(text.substring(start).trim());
        if (parts.size() > 64 || parts.stream().anyMatch(String::isEmpty)) throw invalid();
        return parts;
    }

    public java.util.function.Predicate<ItemStack> compile() {
        return compile(valueSearch == null ? List.of()
                : cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy());
    }

    public java.util.function.Predicate<ItemStack> compile(List<String> whitelist) {
        Map<String,Object> expected = compound == null ? Map.of() : ItemComponents.parse(compound);
        boolean componentName = valueSearch != null && id != null
                && org.bukkit.Registry.DATA_COMPONENT_TYPE.get(org.bukkit.NamespacedKey.fromString(id)) != null;
        org.bukkit.Material materialName = valueSearch != null && id != null ? org.bukkit.Material.matchMaterial(id) : null;
        boolean materialType = materialName != null && materialName.isItem();
        Set<String> searchable = new LinkedHashSet<>();
        if (valueSearch != null && !componentName && !materialType) for (String key : whitelist) {
            if (key != null && key.trim().matches("(?i)(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) searchable.add(componentId(key));
        }
        // Preserve the original string's case and compare typed leaves, independent of plugin/key names.
        Object nestedValue = valueSearch == null || searchable.isEmpty() ? null
                : ItemComponents.parse("{\"coq:value\":\"" + valueSearch.replace("\\","\\\\").replace("\"","\\\"") + "\"}").get("coq:value");
        return item -> {
            Set<String> types = new HashSet<>();
            item.getDataTypes().forEach(type -> types.add(type.getKey().toString()));
            if (valueSearch != null) {
                if (componentName) {
                    if (!types.contains(id)) return false;
                } else if (materialType) {
                    if (item.getType() != materialName) return false;
                } else {
                    if (nestedValue == null || Collections.disjoint(searchable,types)) return false;
                    if (!ItemComponents.containsNestedValue(ItemComponents.values(item,searchable).values(),nestedValue)) return false;
                }
            } else if (id != null && !item.getType().getKey().toString().equals(id)
                    && (itemOnly || !types.contains(id))) {
                return false;
            }
            return types.containsAll(present) && (expected.isEmpty()
                    || ItemComponents.matches(expected,ItemComponents.values(item,expected.keySet())));
        };
    }

    public static String componentId(String input) {
        String id = input.trim();
        if (id.length() >= 2 && (id.charAt(0) == '"' || id.charAt(0) == '\'') && id.charAt(id.length()-1) == id.charAt(0))
            id = id.substring(1,id.length()-1).trim();
        id = id.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid();
        return id;
    }
    private static QueryException invalid() {
        return new QueryException("INVALID_COMPONENT_CONTENT",
                "content: 可填组件名、白名单组件内的字符串值（如 smc:dou_dizhu_table），或 map_id=101205。复杂值可从 /coq debug item 复制条件（最多 16384 字符）。");
    }
}
