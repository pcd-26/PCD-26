package pcd.poool.model.physics.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pcd.poool.model.physics.taskbased.ExecutorRangeScheduler;
import pcd.poool.model.physics.threaded.PlatformThreadRangeScheduler;

class RangeSchedulerTest {

    @Test
    void platformSchedulerCoversEveryItemExactlyOnce() {
        try (var scheduler = new PlatformThreadRangeScheduler(4)) {
            var visits = new AtomicInteger[400];
            for (int i = 0; i < visits.length; i++) {
                visits[i] = new AtomicInteger();
            }

            scheduler.execute(visits.length, (from, to, worker) -> {
                for (int i = from; i < to; i++) {
                    visits[i].incrementAndGet();
                }
            });

            for (var visit : visits) {
                assertEquals(1, visit.get());
            }
        }
    }

    @Test
    void executorSchedulerUsesPoolTasksForLargeRanges() {
        try (var scheduler = new ExecutorRangeScheduler(4)) {
            Set<String> threadNames = ConcurrentHashMap.newKeySet();

            var stats = scheduler.execute(1_000, (from, to, worker) ->
                    threadNames.add(Thread.currentThread().getName()));

            assertEquals(4, stats.submittedTasks());
            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("poool-task-physics-worker")));
        }
    }
}
