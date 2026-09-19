package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.BiFunction;

/** Disposable history view. Creative players can copy restored items into their inventory. */
final class ItemLookupPanel implements Listener, AutoCloseable {
    static final int MAX_ROWS = 45;
    private final JavaPlugin plugin;
    private final LookupSessions sessions;
    private final ItemPreparationQueue preparation;
    private final BiFunction<LookupRequest, Long, LookupResult> lookup;
    private final Map<UUID, Panel> panels = new HashMap<>();

    ItemLookupPanel(JavaPlugin plugin, LookupSessions sessions, ItemPreparationQueue preparation,
                    BiFunction<LookupRequest, Long, LookupResult> lookup) {
        this.plugin = plugin; this.sessions = sessions; this.preparation = preparation; this.lookup = lookup;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }

    void open(Player player, LookupSessions.Session session) {
        validate(session.request());
        close(player);
        Panel panel = new Panel(player,session);
        panels.put(player.getUniqueId(),panel);
        player.openInventory(panel.inventory);
        load(panel,session.request().page());
    }

    static void validate(LookupRequest request) {
        if (request.options().countOnly()) throw new QueryException("INCOMPATIBLE_PARAMETER","计数查询没有物品页，请去掉 #count/#sum 后重新查询。");
        if (!request.action().isItem()) throw new QueryException("INCOMPATIBLE_PARAMETER","箱子面板仅用于 item/container 查询。");
        if (request.pageSize() == null || request.pageSize() < 1 || request.pageSize() > MAX_ROWS)
            throw new QueryException("INVALID_PAGE","箱子面板每页最多 45 条；请用 /coq items <页码>:45 调整。不会自动截断当前页。");
    }

    boolean busy(Player player) { Panel panel = panels.get(player.getUniqueId()); return panel != null && panel.busy; }

    private void load(Panel panel,int page) {
        if (!live(panel) || panel.busy) return;
        if (System.currentTimeMillis() >= panel.session.expiresAt()) {
            message(panel,"查询已过期，请重新输入查询条件。"); closeView(panel); return;
        }
        panel.busy = true;
        if (panel.detailsJob != null) { panel.detailsJob.cancel(); panel.detailsJob = null; }
        controls(panel,"正在异步查询第 " + page + " 页…");
        LookupRequest request = panel.session.request().withPage(page,panel.session.request().pageSize());
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                LookupResult result = lookup.apply(request,panel.session.anchor());
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!live(panel)) return; // Closing, quitting or replacing a panel invalidates late completions.
                    if (System.currentTimeMillis() >= panel.session.expiresAt()) {
                        message(panel,"查询已过期，请重新查询。"); closeView(panel); return;
                    }
                    if (!result.success()) { panel.busy = false; controls(panel,result.message()); message(panel,result.message()); return; }
                    try { panel.session = sessions.page(QueryCommands.senderKey(panel.player),page,request.pageSize()); }
                    catch (QueryException expired) { message(panel,expired.getMessage()); closeView(panel); return; }
                    panel.result = result;
                    panel.inventory.clear(); Arrays.fill(panel.items,null); panel.prepared = 0;
                    controls(panel,"正在还原当前页物品…");
                    panel.job = preparation.submit(result.records(), () -> live(panel), (row, prepared) -> {
                        int slot = panel.prepared++;
                        panel.items[slot] = prepared.item();
                        ItemStack display;
                        if (prepared.item() == null) display = icon(Material.BARRIER,"历史物品无法还原", "点击查看记录信息 · " + row.material());
                        else {
                            display = prepared.item().clone();
                            display.setAmount(Math.max(1,Math.min(row.amount(),display.getMaxStackSize())));
                        }
                        panel.inventory.setItem(slot,display);
                    }, () -> {
                        panel.job = null; panel.busy = false;
                        controls(panel,result.records().isEmpty() ? "没有找到记录" : "点击查看详情；创造模式左键领取、右键详情");
                    });
                });
            });
        } catch (RuntimeException ex) {
            panel.busy = false; controls(panel,"无法开始查询，请重试。");
            plugin.getLogger().warning("Unable to load item panel: " + ex.getClass().getSimpleName());
        }
    }

    private void controls(Panel panel,String status) {
        int nav = panel.contentSlots;
        if (panel.result != null && panel.result.page() > 1)
            panel.inventory.setItem(nav,icon(Material.ARROW,"上一页","按相同查询条件翻页"));
        if (panel.result != null && panel.result.page() < panel.result.totalPages())
            panel.inventory.setItem(nav+8,icon(Material.ARROW,"下一页","按相同查询条件翻页"));
        String page = panel.result == null ? "加载中" : "第 " + panel.result.page() + "/" + Math.max(1,panel.result.totalPages()) + " 页";
        panel.inventory.setItem(nav+4,icon(Material.PAPER,page,status,"每页 " + panel.session.request().pageSize()
                + " 条 · 关闭后销毁", "显示数量不超过堆叠上限；记录原始数量见点击详情"));
        panel.inventory.setItem(nav+7,icon(Material.BARRIER,"关闭面板","关闭后释放本页物品数据"));
    }

    private static ItemStack icon(Material type,String name,String... lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name,NamedTextColor.DARK_AQUA));
        meta.lore(Arrays.stream(lore).map(text -> Component.text(text,NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta); return item;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Panel panel)) return;
        event.setCancelled(true); // Never let vanilla move display items or navigation controls.
        if (!live(panel)) return;
        if (!panel.player.hasPermission("coreprotectaddon.command.query")) { closeView(panel); return; }
        if (System.currentTimeMillis() >= panel.session.expiresAt()) {
            message(panel,"查询已过期，请重新查询。"); closeView(panel); return;
        }
        int slot = event.getRawSlot();
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        if (slot == panel.contentSlots+7) { closeView(panel); return; }
        if (panel.busy || System.nanoTime()-panel.lastClick < 250_000_000L) return;
        panel.lastClick = System.nanoTime();
        if (panel.result == null) return;
        if (slot == panel.contentSlots && panel.result.page() > 1) load(panel,panel.result.page()-1);
        else if (slot == panel.contentSlots+8 && panel.result.page() < panel.result.totalPages()) load(panel,panel.result.page()+1);
        else if (slot >= 0 && slot < panel.result.records().size()
                && panel.player.getGameMode() == GameMode.CREATIVE && event.getClick() == ClickType.LEFT) {
            if (panel.items[slot] == null) { message(panel,"此条历史物品无法还原，不能领取。请右键查看记录。"); return; }
            LookupResult selected = panel.result;
            // InventoryClickEvent must finish before modifying the player's inventory.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!live(panel) || panel.busy || panel.result != selected
                        || panel.player.getGameMode() != GameMode.CREATIVE
                        || !panel.player.hasPermission("coreprotectaddon.command.query")
                        || System.currentTimeMillis() >= panel.session.expiresAt()) return;
                ItemStack item = panel.items[slot].clone();
                item.setAmount(Math.max(1,Math.min(selected.records().get(slot).amount(),item.getMaxStackSize())));
                int amount = item.getAmount();
                int remaining = panel.player.getInventory().addItem(item).values().stream().mapToInt(ItemStack::getAmount).sum();
                message(panel,remaining == amount ? "背包已满，请腾出空间后重试。"
                        : "已领取 " + (amount-remaining) + " 个历史物品副本。" + (remaining > 0 ? "背包空间不足，剩余物品未领取。" : ""));
            });
        }
        else if (slot >= 0 && slot < panel.result.records().size() && panel.detailsJob == null) {
            LookupResult selected = panel.result;
            panel.detailsJob = preparation.submitAction(() -> live(panel) && panel.result == selected, () -> {
                panel.detailsJob = null;
                ItemPanelDetails.describe(selected.records().get(slot),selected,panel.session,panel.items[slot])
                        .forEach(line -> LookupRenderer.send(panel.player,line));
            });
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Panel) event.setCancelled(true);
    }
    @EventHandler public void closed(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Panel panel) dispose(panel);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { close(event.getPlayer()); }

    private boolean live(Panel panel) {
        return !panel.closed && panels.get(panel.player.getUniqueId()) == panel && panel.player.isOnline()
                && panel.player.getOpenInventory().getTopInventory() == panel.inventory;
    }
    private void message(Panel panel,String text) { LookupRenderer.send(panel.player,LookupRenderer.notice(text)); }
    private void closeView(Panel panel) {
        dispose(panel);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (panel.player.getOpenInventory().getTopInventory() == panel.inventory) panel.player.closeInventory();
        });
    }
    void close(Player player) {
        Panel panel = panels.get(player.getUniqueId());
        if (panel == null) return;
        dispose(panel);
        if (player.getOpenInventory().getTopInventory() == panel.inventory) player.closeInventory();
    }
    private void dispose(Panel panel) {
        if (panel.closed) return;
        panel.closed = true;
        panels.remove(panel.player.getUniqueId(),panel);
        if (panel.job != null) { panel.job.cancel(); panel.job = null; }
        if (panel.detailsJob != null) { panel.detailsJob.cancel(); panel.detailsJob = null; }
        panel.inventory.clear(); Arrays.fill(panel.items,null); panel.result = null;
    }
    @Override public void close() {
        new ArrayList<>(panels.values()).forEach(p -> close(p.player));
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    private static final class Panel implements InventoryHolder {
        final Player player;
        final Inventory inventory;
        final int contentSlots;
        final ItemStack[] items;
        LookupSessions.Session session;
        LookupResult result;
        ItemPreparationQueue.Job job;
        ItemPreparationQueue.Job detailsJob;
        boolean busy,closed;
        int prepared;
        long lastClick;
        Panel(Player player,LookupSessions.Session session) {
            this.player = player; this.session = session;
            contentSlots = ((session.request().pageSize()+8)/9)*9;
            items = new ItemStack[contentSlots];
            inventory = Bukkit.createInventory(this,contentSlots+9,Component.text("COQ · 历史物品 · 临时面板"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
