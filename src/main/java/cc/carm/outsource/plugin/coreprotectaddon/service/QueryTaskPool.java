package cc.carm.outsource.plugin.coreprotectaddon.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Bounded, plugin-wide workers. A cancelled sender keeps its slot until the worker really exits. */
public final class QueryTaskPool implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final Map<String, QueryCancellation> active = new HashMap<>();
    private boolean closed;

    public QueryTaskPool() { this(2, 8); }
    public QueryTaskPool(int threads, int queued) {
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queued), task -> {
                    Thread thread = new Thread(task, "COQ-query"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized boolean busy(String key) { return active.containsKey(key); }
    public synchronized <T> QueryCancellation submit(String key, int seconds,
            Function<QueryCancellation,T> work, BiConsumer<T,Throwable> done) {
        if (closed) throw new QueryException("QUERY_CANCELLED", "插件正在停用。");
        if (active.containsKey(key)) throw new QueryException("QUERY_BUSY", "上一条查询仍在结束，请稍候。");
        QueryCancellation cancellation = new QueryCancellation(seconds);
        active.put(key,cancellation);
        try {
            workers.execute(() -> {
                T result = null; Throwable failure = null;
                try { cancellation.attach(); result = work.apply(cancellation); cancellation.check(); }
                catch (Throwable ex) { failure = ex; }
                finally {
                    cancellation.detach();
                    Thread.interrupted(); // Never leak a cancelled task's interrupt to its successor.
                    synchronized (this) { active.remove(key,cancellation); }
                }
                done.accept(result,failure);
            });
        } catch (RejectedExecutionException ex) {
            active.remove(key,cancellation);
            throw new QueryException("QUERY_BUSY", "查询队列已满，请稍后重试。");
        }
        return cancellation;
    }
    public synchronized void cancel(String key) {
        QueryCancellation task = active.get(key); if (task != null) task.cancel();
    }
    @Override public synchronized void close() {
        closed = true; active.values().forEach(QueryCancellation::cancel);
        // Drain cancelled queued jobs so their completion callbacks and ownership cleanup still run.
        workers.shutdown();
    }
}
