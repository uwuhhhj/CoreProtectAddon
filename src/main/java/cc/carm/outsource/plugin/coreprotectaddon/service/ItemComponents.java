package cc.carm.outsource.plugin.coreprotectaddon.service;

import io.papermc.paper.datacomponent.DataComponentType;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;

/** Uses the server's own codec for the effective component map, NOT ItemMeta's override-only patch.
 * Values remain typed NBT; equality ignores compound key order but preserves list order and NBT types.
 * Called on the server thread. Reflection isolates the optional, version-dependent Paper bridge.
 */
public final class ItemComponents {
    public static final int MAX_CONTENT = 16384;
    private static volatile Bridge bridge;
    private ItemComponents() { }

    public static Map<String,Object> parse(String input) {
        if (input == null || input.length() > MAX_CONTENT || !input.trim().startsWith("{"))
            throw invalid("content: 请粘贴完整组件条件，例如 {\"minecraft:max_stack_size\":1}。");
        try {
            Map<String,Object> values = api().entries(api().parse.invoke(null,input));
            if (values.isEmpty() || values.size() > 64) throw invalid("组件条件不能为空，最多 64 个组件。");
            for (String key : values.keySet()) if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw invalid("组件 ID 必须包含命名空间：" + key);
            return values;
        } catch (QueryException e) { throw e; }
        catch (ReflectiveOperationException | RuntimeException e) { throw invalid("组件条件不是有效的 SNBT；请重新复制完整条件。"); }
    }

    public static Map<String,Object> values(ItemStack item, Set<String> keys) {
        try {
            Bridge b = api();
            Object stack = b.copy.invoke(null,item);
            Object components = b.components.invoke(stack);
            Set<Object> selected = new HashSet<>();
            for (DataComponentType type : item.getDataTypes()) if (keys.contains(type.getKey().toString()))
                selected.add(b.nativeType.invoke(null,type));
            Object subset = b.filter.invoke(components,(Predicate<Object>) selected::contains);
            Object registry = b.registry.invoke(null);
            Object ops = b.context.invoke(registry,b.nbtOps);
            Object result = b.encode.invoke(b.codec,ops,subset);
            return b.entries(b.result.invoke(result));
        } catch (QueryException e) { throw e; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw unavailable("无法读取完整物品组件；服务器组件接口不兼容。",e);
        }
    }

    public static boolean matches(Map<String,Object> expected, Map<String,Object> actual) {
        return expected.entrySet().stream().allMatch(e -> e.getValue().equals(actual.get(e.getKey())));
    }

    /** Searches values, never field names or serialized substrings, inside selected component trees. */
    public static boolean containsNestedValue(Collection<?> components, Object expected) {
        try {
            Bridge b = api();
            int[] visited = {0};
            for (Object component : components)
                if (containsNestedValue(b,component,expected,0,visited)) return true;
            return false;
        } catch (QueryException e) { throw e; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw unavailable("无法读取历史物品组件的嵌套值。",e);
        }
    }

    private static boolean containsNestedValue(Bridge b,Object node,Object expected,int depth,int[] visited)
            throws ReflectiveOperationException {
        if (++visited[0] > 16384 || depth > 64)
            throw new QueryException("COMPONENT_VALUE_SCAN_LIMIT","组件嵌套数据过多或过深，请使用明确的组件条件缩小筛选范围。");
        if (expected.equals(node)) return true;
        if (node != null && b.get.getDeclaringClass().isInstance(node)) {
            for (Object key : (Set<?>) b.keys.invoke(node))
                if (containsNestedValue(b,b.get.invoke(node,key),expected,depth+1,visited)) return true;
        } else if (node instanceof Iterable<?> children) {
            for (Object child : children)
                if (containsNestedValue(b,child,expected,depth+1,visited)) return true;
        }
        return false;
    }

    /** A whole SNBT compound is directly pasteable after content:, without an additional quote layer. */
    public static String condition(String key,Object value) {
        if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("组件 ID 无效。");
        return "{\"" + key + "\":" + value + "}";
    }
    private static QueryException invalid(String message) { return new QueryException("INVALID_COMPONENT_CONTENT",message); }
    private static QueryException unavailable(String message,Throwable cause) {
        QueryException error = new QueryException("COMPONENT_UNAVAILABLE",message);
        error.initCause(cause); return error;
    }
    private static Bridge api() {
        if (bridge == null) {
            try { bridge = new Bridge(); }
            catch (ReflectiveOperationException | LinkageError e) {
                throw unavailable("当前服务器不支持完整组件编码接口。",e);
            }
        }
        return bridge;
    }

    private static final class Bridge {
        final Method parse,copy,components,nativeType,filter,registry,context,encode,result,keys,get;
        final Object codec,nbtOps;
        Bridge() throws ReflectiveOperationException {
            Class<?> map = Class.forName("net.minecraft.core.component.DataComponentMap");
            Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps");
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            parse = Class.forName("net.minecraft.nbt.TagParser").getMethod("parseCompoundFully",String.class);
            copy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy",ItemStack.class);
            components = Class.forName("net.minecraft.world.item.ItemStack").getMethod("getComponents");
            nativeType = Class.forName("io.papermc.paper.datacomponent.PaperDataComponentType").getMethod("bukkitToMinecraft",DataComponentType.class);
            filter = map.getMethod("filter",Predicate.class);
            codec = map.getField("CODEC").get(null);
            nbtOps = Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            registry = Class.forName("org.bukkit.craftbukkit.CraftRegistry").getMethod("getMinecraftRegistry");
            context = Class.forName("net.minecraft.core.HolderLookup$Provider").getMethod("createSerializationContext",ops);
            encode = Class.forName("com.mojang.serialization.Encoder").getMethod("encodeStart",ops,Object.class);
            result = Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow");
            keys = compound.getMethod("keySet");
            get = compound.getMethod("get",String.class);
        }
        Map<String,Object> entries(Object compound) throws ReflectiveOperationException {
            Map<String,Object> values = new TreeMap<>();
            for (Object key : (Set<?>) keys.invoke(compound)) values.put((String)key,get.invoke(compound,key));
            return Collections.unmodifiableMap(values);
        }
    }
}
