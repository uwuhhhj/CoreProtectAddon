package coqtest;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.selector.EllipsoidRegionSelector;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Dedicated disposable server ONLY: this probe shuts the server down after verification. */
public class SpatialProbe extends JavaPlugin {
    @Override public void onEnable() {
        getServer().getScheduler().runTask(this,()->{
            try {
                org.bukkit.World world=getServer().getWorlds().get(0);
                UUID id=UUID.randomUUID();
                Player player=(Player)Proxy.newProxyInstance(getClassLoader(),new Class[]{Player.class},(proxy,method,args)->switch(method.getName()) {
                    case "getUniqueId" -> id; case "getName" -> "SpatialFixture"; case "getWorld" -> world;
                    case "getLocation" -> new Location(world,-1.2,64,3); case "getServer" -> getServer();
                    case "isOnline","isValid","hasPermission","isOp" -> true;
                    case "getMetadata" -> List.of();case "getLocale" -> "en_us";
                    case "hashCode" -> id.hashCode();case "equals" -> proxy==args[0];case "toString" -> "SpatialFixture";
                    default -> method.getReturnType()==boolean.class ? false : method.getReturnType()==int.class ? 0
                            : method.getReturnType()==long.class ? 0L : method.getReturnType()==double.class ? 0D
                            : method.getReturnType()==float.class ? 0F : null;
                });
                var actor=BukkitAdapter.adapt(player);
                var session=WorldEdit.getInstance().getSessionManager().get(actor);
                var weWorld=BukkitAdapter.adapt(world);
                var request=LookupParameters.parse(new String[]{"l","t:1h","r:#we"}).request();
                try { SpatialResolver.resolve(player,request);throw new AssertionError("Incomplete selection accepted"); }
                catch(QueryException expected) {check(expected.code().equals("INVALID_SELECTION"),expected.code());}
                session.setRegionSelector(weWorld,new CuboidRegionSelector(weWorld,BlockVector3.at(-2,60,-3),BlockVector3.at(4,70,5)));
                LookupRequest first=SpatialResolver.resolve(player,request);
                check(first.options().bounds().equals(new SpatialBounds(world.getName(),-2,4,60,70,-3,5)),"cuboid");
                session.setRegionSelector(weWorld,new EllipsoidRegionSelector(weWorld,BlockVector3.at(10,65,10),com.sk89q.worldedit.math.Vector3.at(3,2,4)));
                LookupRequest second=SpatialResolver.resolve(player,request);
                check(second.options().bounds().equals(new SpatialBounds(world.getName(),7,13,63,67,6,14)),"ellipsoid bounding box");
                check(first.withPage(2,15).options().bounds().minX()==-2,"immutable paging snapshot");
                var near=SpatialResolver.resolve(player,LookupParameters.parse(new String[]{"near","t:1h"}).request());
                check(near.options().bounds().equals(new SpatialBounds(world.getName(),-7,3,59,69,-2,8)),"near location floor");
                var tagged=LookupParameters.parse(new String[]{"l","t:1h","i:#door"}).request();
                var resolve=LookupParameters.class.getDeclaredMethod("resolveTags",LookupRequest.class);resolve.setAccessible(true);
                var expanded=(LookupRequest)resolve.invoke(null,tagged);
                check(expanded.include().contains("minecraft:oak_door"),"native CoreProtect block tags");
                Files.writeString(Path.of("coq-spatial-probe.txt"),"PASS: WorldEdit selection, ellipsoid bounds, immutable paging, near, native tags\n");
            } catch(Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE,"Spatial probe failed",failure);
                try {Files.writeString(Path.of("coq-spatial-probe.txt"),"FAIL: "+failure+"\n");}catch(Exception ignored){}
            } finally {getServer().shutdown();}
        });
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
