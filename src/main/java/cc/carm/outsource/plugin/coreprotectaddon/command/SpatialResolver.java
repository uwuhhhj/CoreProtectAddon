package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/** Copies sender/WorldEdit state on the server thread; SQL receives only immutable values. */
public final class SpatialResolver {
    private SpatialResolver() { }
    public static void validate(LookupOptions options) {
        String radius = options.radius();
        if (radius != null && !radius.startsWith("#") && !global(radius)) radii(radius);
        if (options.coordinates() != null) coordinates(options.coordinates());
    }
    public static LookupRequest resolve(CommandSender sender, LookupRequest request) {
        LookupOptions options = request.options();
        if (!options.unresolved()) return request;
        String radius = options.radius();
        if (radius != null && (radius.equalsIgnoreCase("#we") || radius.equalsIgnoreCase("#worldedit"))) {
            if (options.world() != null || options.coordinates() != null)
                throw error("INVALID_LOCATION", "WE 选区不能与 world:/coord: 混用。");
            if (!(sender instanceof Player player)) throw error("PLAYER_ONLY", "WE 选区查询只能由玩家执行。");
            return request.withOptions(options.resolved(selection(player)));
        }
        Location location = sender instanceof Player player ? player.getLocation()
                : sender instanceof BlockCommandSender block ? block.getBlock().getLocation() : null;
        SpatialBounds bounds = resolve(options, location == null ? null : location.getWorld().getName(),
                location == null ? 0 : location.getBlockX(), location == null ? 0 : location.getBlockY(),
                location == null ? 0 : location.getBlockZ());
        return request.withOptions(options.resolved(bounds));
    }
    static SpatialBounds resolve(LookupOptions options, String senderWorld, int x, int y, int z) {
        validate(options);
        String radius = options.radius(), world = options.world();
        if (radius != null && global(radius)) {
            if (world != null || options.coordinates() != null) throw error("INVALID_LOCATION", "r:#global 不能与世界/坐标混用。");
            return null;
        }
        if (radius != null && radius.startsWith("#")) {
            String named = radius.substring(1);
            if (world != null && !world.equals(named)) throw error("INVALID_LOCATION", "重复指定了不同的世界。");
            world = named; radius = null;
        }
        if (world == null) world = senderWorld;
        if (world == null || world.isBlank()) throw error("LOCATION_REQUIRED", "控制台查询范围须提供 world: 和 coord:。");
        if (options.coordinates() != null) {
            int[] point = coordinates(options.coordinates()); x = point[0]; y = point[1]; z = point[2];
            if (radius == null) radius = "0";
        } else if (radius != null && senderWorld == null) throw error("LOCATION_REQUIRED", "半径查询缺少中心，请提供 coord:x,y,z。");
        if (radius == null) return new SpatialBounds(world,null,null,null,null,null,null);
        int[] r = radii(radius);
        try {
            return new SpatialBounds(world, Math.subtractExact(x,r[0]),Math.addExact(x,r[0]),
                    r[1] < 0 ? null : Math.subtractExact(y,r[1]),r[1] < 0 ? null : Math.addExact(y,r[1]),
                    Math.subtractExact(z,r[2]),Math.addExact(z,r[2]));
        } catch (ArithmeticException ex) { throw error("INVALID_RADIUS", "半径超出坐标范围。"); }
    }
    static int[] radii(String value) {
        String[] parts = value.toLowerCase(Locale.ROOT).split("x",-1);
        if (parts.length < 1 || parts.length > 3) throw error("INVALID_RADIUS", "半径格式为 r:5、r:5x3 或 r:5x3x8。");
        int[] result = {0,-1,0};
        for (int i=0;i<parts.length;i++) {
            if (!parts[i].matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw error("INVALID_RADIUS", "半径必须为非负数字。");
            try {
                double n = Double.parseDouble(parts[i]);
                if (!Double.isFinite(n) || n > Integer.MAX_VALUE) throw new NumberFormatException();
                result[i] = (int)n;
            } catch (NumberFormatException ex) { throw error("INVALID_RADIUS", "半径超出范围。"); }
        }
        if (parts.length < 3) result[2] = result[0];
        return result;
    }
    private static int[] coordinates(String value) {
        String[] parts = value.split(",",-1);
        if (parts.length != 2 && parts.length != 3) throw error("INVALID_LOCATION", "坐标格式为 coord:x,z 或 coord:x,y,z。");
        int[] point = new int[3];
        for (int i=0;i<parts.length;i++) try {
            double n = Double.parseDouble(parts[i]);
            if (!Double.isFinite(n) || Math.floor(n) < Integer.MIN_VALUE || Math.floor(n) > Integer.MAX_VALUE) throw new NumberFormatException();
            point[parts.length == 2 && i == 1 ? 2 : i] = (int)Math.floor(n);
        } catch (NumberFormatException ex) { throw error("INVALID_LOCATION", "坐标必须为范围内的有限数字。"); }
        return point;
    }
    private static boolean global(String value) {
        return java.util.Set.of("#global","global","off","-1","none","false").contains(value.toLowerCase(Locale.ROOT));
    }
    private static SpatialBounds selection(Player player) {
        Plugin plugin = player.getServer().getPluginManager().getPlugin("WorldEdit");
        if (plugin == null) plugin = player.getServer().getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (plugin == null || !plugin.isEnabled()) throw error("WORLDEDIT_UNAVAILABLE", "未启用 WorldEdit/FAWE。");
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter",true,loader);
            Object actor = adapter.getMethod("adapt",Player.class).invoke(null,player);
            Class<?> worldEdit = Class.forName("com.sk89q.worldedit.WorldEdit",true,loader);
            Object manager = worldEdit.getMethod("getSessionManager").invoke(worldEdit.getMethod("getInstance").invoke(null));
            Class<?> owner = Class.forName("com.sk89q.worldedit.session.SessionOwner",true,loader);
            Object session = manager.getClass().getMethod("get",owner).invoke(manager,actor);
            Object world = session.getClass().getMethod("getSelectionWorld").invoke(session);
            if (world == null) throw error("INVALID_SELECTION", "请先完成 WorldEdit 选区。");
            Class<?> worldClass = Class.forName("com.sk89q.worldedit.world.World",true,loader);
            String name = (String)worldClass.getMethod("getName").invoke(world);
            if (!name.equals(player.getWorld().getName())) throw error("INVALID_SELECTION", "WE 选区不在当前世界。");
            Object region = session.getClass().getMethod("getSelection",worldClass).invoke(session,world);
            Class<?> regionClass = Class.forName("com.sk89q.worldedit.regions.Region",true,loader);
            Object min = regionClass.getMethod("getMinimumPoint").invoke(region);
            Object max = regionClass.getMethod("getMaximumPoint").invoke(region);
            Class<?> vector = Class.forName("com.sk89q.worldedit.math.BlockVector3",true,loader);
            return new SpatialBounds(name,axis(vector,min,"X"),axis(vector,max,"X"),axis(vector,min,"Y"),axis(vector,max,"Y"),axis(vector,min,"Z"),axis(vector,max,"Z"));
        } catch (InvocationTargetException ex) {
            if (ex.getCause().getClass().getSimpleName().equals("IncompleteRegionException"))
                throw error("INVALID_SELECTION", "请先完成 WorldEdit 选区。");
            throw error("WORLDEDIT_UNAVAILABLE", "读取 WorldEdit 选区失败：" + ex.getCause().getClass().getSimpleName());
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw error("WORLDEDIT_UNAVAILABLE", "WorldEdit API 不兼容：" + ex.getClass().getSimpleName());
        }
    }
    private static int axis(Class<?> vector,Object value,String axis) throws ReflectiveOperationException {
        return ((Number)vector.getMethod("getBlock"+axis).invoke(value)).intValue();
    }
    private static QueryException error(String code,String text) { return new QueryException(code,text); }
}
