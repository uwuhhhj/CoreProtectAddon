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
    private final java.util.Set<CompletableFuture<BitSet>> searches = ConcurrentHashMap.newKeySet();

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

    /** Called only by a query worker. Waiting does not block the server thread. */
    BitSet match(List<LookupRecord> records,String content,long deadline) {
        if (org.bukkit.Bukkit.isPrimaryThread()) throw new QueryException("ASYNC_REQUIRED","组件查询必须在异步线程执行。");
        CompletableFuture<BitSet> answer = new CompletableFuture<>();
        searches.add(answer);
        try {
            if (closed) throw new QueryException("QUERY_CANCELLED","插件正在停用。");
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (answer.isDone() || closed) return;
                try {
                    var expected = cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.parse(content).compile();
                    if (records.isEmpty()) { answer.complete(new BitSet()); return; }
                    BitSet matches = new BitSet(records.size());
                    int[] index = {0};
                    submit(records, () -> !answer.isDone() && !closed && System.nanoTime() < deadline, (record, prepared) -> {
                        if (answer.isDone()) return;
                        try {
                            if (prepared.item() == null) throw new QueryException("COMPONENT_DECODE_FAILED",
                                    "候选记录 #" + record.rowId() + " 的历史组件无法还原；请缩小查询范围。");
                            if (expected.test(prepared.item())) matches.set(index[0]);
                            index[0]++;
                        } catch (RuntimeException ex) { answer.completeExceptionally(ex); }
                    }, () -> answer.complete(matches),false);
                } catch (RuntimeException | LinkageError ex) { answer.completeExceptionally(ex); }
            });
            long remaining = deadline-System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return answer.get(remaining,TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new QueryException("QUERY_CANCELLED","组件查询已取消。");
        } catch (TimeoutException ex) {
            throw new QueryException("QUERY_TIMEOUT","组件查询超时，请缩小时间范围或增加玩家、物品条件。");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof QueryException query) throw query;
            throw new QueryException("COMPONENT_UNAVAILABLE","无法解析历史物品组件。");
        } finally { answer.cancel(false); searches.remove(answer); }
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
