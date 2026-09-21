package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRecord;
import cc.carm.outsource.plugin.coreprotectaddon.service.HistoricalItemDecoder;
import cc.carm.outsource.plugin.coreprotectaddon.service.ItemComponents;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.List;
import java.util.BitSet;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** One shared main-thread budget for ALL viewers and chat results; round robin, no prefetch. */
final class ItemPreparationQueue implements AutoCloseable {
    static final int ITEMS_PER_TICK = 2;
    static final long NANOS_PER_TICK = 2_000_000;
    private final JavaPlugin plugin;
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private BukkitTask task;
    private volatile boolean closed;
    private final java.util.Set<CompletableFuture<?>> searches = ConcurrentHashMap.newKeySet();

    record Prepared(ItemStack item, HoverEvent<?> hover) { }

    ItemPreparationQueue(JavaPlugin plugin) { this.plugin = plugin; }

    Job submit(List<LookupRecord> records, BooleanSupplier valid,
               BiConsumer<LookupRecord, Prepared> consumer, Runnable done) {
        return submit(records,valid,consumer,done,true);
    }
    Job submitAction(BooleanSupplier valid,Runnable action) {
        return submit(List.of(),valid,(record,prepared) -> { },action,false);
    }
    private Job submit(List<LookupRecord> records,BooleanSupplier valid,
                       BiConsumer<LookupRecord,Prepared> consumer,Runnable done,boolean hover) {
        Job job = new Job(records, valid, consumer, done,hover);
        jobs.add(job);
        if (task == null) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        return job;
    }

    cc.carm.outsource.plugin.coreprotectaddon.service.ComponentMatcher matcher() {
        return new cc.carm.outsource.plugin.coreprotectaddon.service.ComponentMatcher() {
            @Override public BitSet match(List<LookupRecord> rows,String content,long deadline) {
                return prepare(content,deadline).match(rows,content,deadline);
            }
            @Override public cc.carm.outsource.plugin.coreprotectaddon.service.ComponentMatcher prepare(String content,long deadline) {
                var compiled = awaitMain(() -> cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.parse(content)
                        .compileSnapshot(cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy()),deadline);
                return (rows,ignored,end) -> matchSnapshots(rows,compiled,end);
            }
        };
    }

    /** Compatibility entry point for runtime probes. A real query prepares its predicate once. */
    BitSet match(List<LookupRecord> rows,String content,long deadline) { return matcher().match(rows,content,deadline); }

    private BitSet matchSnapshots(List<LookupRecord> records,
            cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.Compiled compiled,long deadline) {
        CompletableFuture<List<cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.Snapshot>> answer = new CompletableFuture<>();
        searches.add(answer);
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (answer.isDone() || closed) return;
                if (records.isEmpty()) { answer.complete(List.of()); return; }
                var snapshots = new java.util.ArrayList<cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.Snapshot>();
                submit(records, () -> !answer.isDone() && !closed && System.nanoTime() < deadline, (record,prepared) -> {
                    try {
                        if (prepared.item() == null) throw new QueryException("COMPONENT_DECODE_FAILED",
                                "候选记录 #" + record.rowId() + " 的历史组件无法还原。");
                        snapshots.add(compiled.capture(prepared.item()));
                    } catch (RuntimeException | LinkageError ex) { answer.completeExceptionally(ex); }
                }, () -> answer.complete(List.copyOf(snapshots)),false);
            });
            var snapshots = await(answer,deadline);
            BitSet result = new BitSet(snapshots.size());
            for (int i=0;i<snapshots.size();i++) {
                checkWorker(deadline);
                if (compiled.test(snapshots.get(i))) result.set(i);
            }
            return result;
        } finally { answer.cancel(false); searches.remove(answer); }
    }

    private <T> T awaitMain(Callable<T> work,long deadline) {
        checkWorker(deadline);
        CompletableFuture<T> answer = new CompletableFuture<>(); searches.add(answer);
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (answer.isDone() || closed || System.nanoTime() >= deadline) return;
                try { answer.complete(work.call()); }
                catch (Exception | LinkageError ex) { answer.completeExceptionally(ex); }
            });
            return await(answer,deadline);
        } finally { answer.cancel(false); searches.remove(answer); }
    }
    private void checkWorker(long deadline) {
        if (org.bukkit.Bukkit.isPrimaryThread()) throw new QueryException("ASYNC_REQUIRED","组件查询必须在异步线程执行。");
        if (closed || Thread.currentThread().isInterrupted()) throw new QueryException("QUERY_CANCELLED","查询已取消。");
        if (System.nanoTime() >= deadline) throw new QueryException("QUERY_TIMEOUT","组件查询超时，搜索未完成。");
    }
    private <T> T await(CompletableFuture<T> answer,long deadline) {
        checkWorker(deadline);
        try { return answer.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new QueryException("QUERY_CANCELLED","组件查询已取消。");
        } catch (TimeoutException ex) { throw new QueryException("QUERY_TIMEOUT","组件查询超时，搜索未完成。"); }
        catch (ExecutionException ex) {
            if (ex.getCause() instanceof QueryException query) throw query;
            throw new QueryException("COMPONENT_UNAVAILABLE","无法解析历史物品组件。");
        }
    }

    private void tick() {
        long start = System.nanoTime();
        int processed = 0;
        // An individual Bukkit item operation cannot be preempted; check budget after each item.
        while (!jobs.isEmpty() && processed < ITEMS_PER_TICK && System.nanoTime() - start < NANOS_PER_TICK) {
            Job job = jobs.removeFirst();
            if (job.cancelled || !job.valid.getAsBoolean()) { job.cancel(); continue; }
            if (job.index < job.records.size()) {
                LookupRecord record = job.records.get(job.index++);
                job.consumer.accept(record, prepare(record,job.hover));
            }
            processed++; // Click-detail work also consumes the shared per-tick budget.
            if (job.cancelled) continue;
            if (job.index == job.records.size()) {
                Runnable done = job.done;
                job.cancel();
                done.run();
            } else jobs.addLast(job);
        }
        if (jobs.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    static Prepared prepare(LookupRecord record) {
        return prepare(record,true);
    }
    private static Prepared prepare(LookupRecord record,boolean hover) {
        try {
            ItemStack item = HistoricalItemDecoder.decode(record);
            return new Prepared(item, hover ? item.asHoverEvent() : null);
        } catch (Exception | LinkageError ex) {
            return new Prepared(null, HoverEvent.showText(Component.text(
                    "此条历史物品详情无法还原（元数据损坏或版本不兼容）。", NamedTextColor.RED)));
        }
    }

    @Override public void close() {
        closed = true;
        searches.forEach(f -> f.completeExceptionally(new QueryException("QUERY_CANCELLED","插件正在停用。")));
        searches.clear();
        while (!jobs.isEmpty()) jobs.removeFirst().cancel();
        if (task != null) { task.cancel(); task = null; }
    }

    final class Job {
        private List<LookupRecord> records;
        private BooleanSupplier valid;
        private BiConsumer<LookupRecord, Prepared> consumer;
        private Runnable done;
        private int index;
        private boolean cancelled;
        private final boolean hover;
        Job(List<LookupRecord> records, BooleanSupplier valid, BiConsumer<LookupRecord, Prepared> consumer, Runnable done,boolean hover) {
            this.records = records; this.valid = valid; this.consumer = consumer; this.done = done; this.hover = hover;
        }
        void cancel() {
            cancelled = true; records = List.of(); valid = null; consumer = null; done = null;
            jobs.remove(this);
        }
    }
}
