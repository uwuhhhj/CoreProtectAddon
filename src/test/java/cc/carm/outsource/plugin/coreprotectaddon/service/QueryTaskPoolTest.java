package cc.carm.outsource.plugin.coreprotectaddon.service;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class QueryTaskPoolTest {
    @Test public void deadlineIsCheckedBeforeStartingWork() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(); CompletableFuture<Throwable> done = new CompletableFuture<>();
        try (QueryTaskPool pool = new QueryTaskPool(1,1)) {
            pool.submit("expired",0,c -> { executed.set(true); return 1; },(r,e) -> done.complete(e));
            assertEquals("QUERY_TIMEOUT",((QueryException)done.get(2,TimeUnit.SECONDS)).code());
            assertFalse(executed.get());
        }
    }
    @Test public void closingAViewCannotReleaseItsRunningQueryOrCreateAnUnboundedQueue() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(2);
        try (QueryTaskPool pool = new QueryTaskPool(1,1)) {
            QueryCancellation task = pool.submit("player",5,cancel -> {
                started.countDown();
                // Simulate a JDBC driver that does not stop immediately on interrupt.
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                return 1;
            },(result,failure) -> finished.countDown());
            assertTrue(started.await(2,TimeUnit.SECONDS));
            task.cancel();
            assertTrue(pool.busy("player"));
            assertEquals("QUERY_BUSY",assertThrows(QueryException.class,
                    () -> pool.submit("player",5,c -> 2,(r,e) -> { })).code());
            pool.submit("second",5,c -> 2,(r,e) -> finished.countDown());
            assertEquals("QUERY_BUSY",assertThrows(QueryException.class,
                    () -> pool.submit("third",5,c -> 3,(r,e) -> { })).code());
            release.countDown();
            assertTrue(finished.await(2,TimeUnit.SECONDS));
            assertFalse(pool.busy("player")); assertFalse(pool.busy("third"));
            CompletableFuture<Boolean> reused = new CompletableFuture<>();
            pool.submit("player",5,c -> Thread.currentThread().isInterrupted(),(r,e) -> reused.complete(r));
            assertFalse(reused.get(2,TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
    @Test public void queuedCancellationNeverRunsWorkAndShutdownRejectsNewWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Throwable> cancelled = new CompletableFuture<>();
        AtomicBoolean executed = new AtomicBoolean();
        QueryTaskPool pool = new QueryTaskPool(1,1);
        try {
            pool.submit("first",5,c -> {
                started.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return 1;
            },(r,e) -> { });
            assertTrue(started.await(2,TimeUnit.SECONDS));
            pool.submit("queued",5,c -> { executed.set(true); return 2; },(r,e) -> cancelled.complete(e)).cancel();
            release.countDown();
            assertEquals("QUERY_CANCELLED",((QueryException)cancelled.get(2,TimeUnit.SECONDS)).code());
            assertFalse(executed.get());
            pool.close();
            assertThrows(QueryException.class,() -> pool.submit("new",5,c -> 3,(r,e) -> { }));
        } finally { release.countDown(); pool.close(); }
    }
}
