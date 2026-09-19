package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real Paper inventories/scheduler/events with a synthetic viewer; no network client required. */
public final class PanelProbe {
    private final JavaPlugin plugin;
    private final LookupRecord row;
    private final LookupSessions sessions = new LookupSessions(Clock.systemUTC(),300,256);
    private final ItemPreparationQueue queue;
    private final ItemLookupPanel controller;
    private final AtomicInteger queries = new AtomicInteger();
    private final List<Component> messages = new ArrayList<>();
    private final List<Long> anchors = Collections.synchronizedList(new ArrayList<>());
    private final UUID uuid = UUID.randomUUID();
    private final Inventory bottom = Bukkit.createInventory(null,36);
    private Inventory top = bottom;
    private final Player player;
    private final InventoryView view;
    private int opens;
    private volatile boolean delay;
    private GameMode gameMode = GameMode.SURVIVAL;
    private final PlayerInventory backpack = (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PlayerInventory.class},
            (p,m,args) -> {
                if (m.getName().equals("addItem")) return bottom.addItem((ItemStack[])args[0]);
                throw new UnsupportedOperationException(m.getName());
            });

    public PanelProbe(JavaPlugin plugin,LookupRecord row) {
        this.plugin = plugin; this.row = row;
        queue = new ItemPreparationQueue(plugin);
        player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Player.class},(p,m,args) -> switch(m.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> "COQProbe";
            case "getGameMode" -> gameMode;
            case "getInventory" -> backpack;
            case "isOnline", "hasPermission" -> true;
            case "getOpenInventory" -> currentView();
            case "openInventory" -> { top = (Inventory) args[0]; opens++; yield currentView(); }
            case "closeInventory" -> { InventoryCloseEvent event = new InventoryCloseEvent(currentView());
                Bukkit.getPluginManager().callEvent(event); top = bottom; yield null; }
            case "sendMessage" -> { if (args[0] instanceof Component c) messages.add(c); yield null; }
            case "equals" -> p == args[0];
            case "hashCode" -> uuid.hashCode();
            case "toString" -> "COQProbe";
            default -> null;
        });
        view = (InventoryView) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{InventoryView.class},(p,m,args) -> switch(m.getName()) {
            case "getTopInventory" -> top;
            case "getBottomInventory" -> bottom;
            case "getPlayer" -> player;
            case "getType" -> InventoryType.CHEST;
            case "getTitle", "getOriginalTitle" -> "COQProbe";
            case "countSlots" -> top.getSize()+bottom.getSize();
            case "convertSlot" -> (int) args[0] < top.getSize() ? args[0] : (int) args[0]-top.getSize();
            case "getItem" -> (int) args[0] < top.getSize() ? top.getItem((int)args[0]) : bottom.getItem((int)args[0]-top.getSize());
            default -> null;
        });
        controller = new ItemLookupPanel(plugin,sessions,queue,(request,anchor) -> {
            check(!Bukkit.isPrimaryThread(),"SQL must run asynchronously");
            queries.incrementAndGet(); anchors.add(anchor);
            if (delay) try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            List<LookupRecord> rows = request.page() == 1 ? List.of(row,row,row) : List.of(row);
            return new LookupResult(true,null,"ok",request.action(),request.page(),3,4,false,1,rows);
        });
    }
    private InventoryView currentView() { return view; }

    public void run() {
        Map<Integer,Integer> tickCounts = new HashMap<>();
        AtomicInteger done = new AtomicInteger();
        for (int j = 0; j < 2; j++) queue.submit(Collections.nCopies(4,row),() -> true,
                (record, prepared) -> tickCounts.merge(Bukkit.getCurrentTick(),1,Integer::sum), () -> {
                    if (done.incrementAndGet() == 2) safely(() -> {
                        check(tickCounts.values().stream().allMatch(n -> n <= 2),"shared decode budget across jobs");
                        startPanel();
                    });
                });
    }

    private void startPanel() {
        LookupRequest request = new LookupRequest(LookupAction.ITEM,List.of("Loliiiico"),"1h",1,3,List.of("potion"),List.of(),null);
        LookupSessions.Session session = sessions.start("player:"+uuid,request);
        controller.open(player,session);
        Inventory first = top;
        waitFor(() -> !controller.busy(player), () -> {
            check(first.getItem(0).getType() == Material.POTION && first.getItem(2).getType() == Material.POTION,"exactly current page items");
            check(first.getItem(3) == null,"no prefetched items");
            for (ClickType type : List.of(ClickType.LEFT,ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP)) {
                InventoryClickEvent event = type == ClickType.NUMBER_KEY
                        ? new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,0,type,InventoryAction.HOTBAR_SWAP,0)
                        : new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,0,type,InventoryAction.PICKUP_ALL);
                Bukkit.getPluginManager().callEvent(event); check(event.isCancelled(),"blocked " + type);
            }
            InventoryClickEvent bottomClick = new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,first.getSize(),ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY);
            Bukkit.getPluginManager().callEvent(bottomClick); check(bottomClick.isCancelled(),"blocked bottom shift click");
            InventoryDragEvent drag = new InventoryDragEvent(view,null,new ItemStack(Material.DIAMOND),false,Map.of(0,new ItemStack(Material.DIAMOND)));
            Bukkit.getPluginManager().callEvent(drag); check(drag.isCancelled(),"blocked drag");
            waitFor(() -> !messages.isEmpty(), () -> {
            String text = String.join("\n",messages.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList());
            check(text.contains("minecraft:custom_name") && text.contains("被封存") && text.contains("记录 #"),"component and query chat details: " + text);
            creativeChecks(() -> {
            long after = System.nanoTime()+300_000_000L;
            waitFor(() -> System.nanoTime() >= after, () -> {
                InventoryClickEvent next = new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,17,ClickType.LEFT,InventoryAction.PICKUP_ALL);
                Bukkit.getPluginManager().callEvent(next);
                check(next.isCancelled(),"navigation read only");
                waitFor(() -> !controller.busy(player), () -> {
                    check(queries.get()==2,"single next-page query");
                    check(first.getItem(0).getType()==Material.POTION && first.getItem(1)==null,"previous page released");
                    check(sessions.require("player:"+uuid).request().page()==2,"chat and GUI page synchronized");
                    check(anchors.get(0).equals(anchors.get(1)),"fixed time anchor");
                    player.closeInventory();
                    check(first.isEmpty(),"close clears inventory");
                    delay = true;
                    controller.open(player,sessions.require("player:"+uuid));
                    Inventory abandoned = top;
                    player.closeInventory();
                    int expectedOpens = opens;
                    long returned = System.nanoTime()+500_000_000L;
                    waitFor(() -> queries.get()==3 && System.nanoTime() >= returned, () -> {
                        check(opens==expectedOpens && top==bottom && abandoned.isEmpty(),"late query cannot resurrect closed panel");
                        controller.close(); queue.close();
                        Files.writeString(Path.of("coq-panel-probe.txt"),"PASS: creative claim metadata/amount; survival and mode-change protection; full backpack; shared decode budget; async query; current page only; native inventory items; component details; click/shift/hotbar/double/drop/drag protection; next page; shared chat page and anchor; disposal; late-query cancellation.\n" + Bukkit.getVersion());
                        new ContentProbe(plugin,row).run();
                    });
                });
            });
            });
            });
        });
    }
    private void click(int slot,ClickType type) throws Exception {
        var field = top.getHolder().getClass().getDeclaredField("lastClick");
        field.setAccessible(true); field.setLong(top.getHolder(),0);
        var event = new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,type,InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(event);
        check(event.isCancelled(),"native inventory actions remain cancelled");
    }
    private void creativeChecks(Checked done) throws Exception {
        check(bottom.isEmpty(),"survival cannot claim items");
        gameMode = GameMode.CREATIVE;
        click(0,ClickType.LEFT);
        Bukkit.getScheduler().runTask(plugin,() -> safely(() -> {
            ItemStack expected = top.getItem(0);
            check(bottom.getItem(0).equals(expected),"creative receives bounded amount and full metadata");
            check(top.getItem(0).equals(expected),"claim preserves history display");
            bottom.clear();
            click(0,ClickType.RIGHT);
            click(0,ClickType.SHIFT_LEFT);
            click(0,ClickType.DOUBLE_CLICK);
            click(0,ClickType.MIDDLE);
            // A mode change before the scheduled give must prevent a claim.
            click(0,ClickType.LEFT); gameMode = GameMode.SURVIVAL;
            Bukkit.getScheduler().runTask(plugin,() -> safely(() -> {
                check(bottom.isEmpty(),"right/shift/double/middle and mode-change cannot claim");
                gameMode = GameMode.CREATIVE;
                for (int i=0;i<bottom.getSize();i++) bottom.setItem(i,new ItemStack(Material.STONE,64));
                click(0,ClickType.LEFT);
                Bukkit.getScheduler().runTask(plugin,() -> safely(() -> {
                    check(bottom.all(Material.POTION).isEmpty(),"full backpack never overwrites existing items");
                    check(messages.stream().anyMatch(c -> PlainTextComponentSerializer.plainText().serialize(c).contains("背包已满")),"full backpack feedback");
                    bottom.clear(); gameMode = GameMode.SURVIVAL;
                    done.run();
                }));
            }));
        }));
    }
    private void waitFor(BooleanSupplier condition,Checked task) {
        new BukkitRunnable() {
            int ticks;
            @Override public void run() { safely(() -> {
                check(++ticks < 200,"probe timeout");
                if (condition.getAsBoolean()) { cancel(); task.run(); }
            }); }
        }.runTaskTimer(plugin,1,1);
    }
    private void safely(Checked task) {
        try { task.run(); } catch (Throwable failure) {
            failure.printStackTrace(); controller.close(); queue.close();
            try { Files.writeString(Path.of("coq-panel-probe.txt"),"FAIL: " + failure); } catch (Exception ignored) { }
            Bukkit.shutdown();
        }
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
