package com.example.tcpprobe.store;

import com.example.tcpprobe.TestFixtures;
import com.example.tcpprobe.config.ProbeProperties;
import com.example.tcpprobe.domain.TaskState;
import com.example.tcpprobe.model.ProbeTarget;
import com.example.tcpprobe.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStoreTest {

    private static TaskState task(String id) {
        return new TaskState(id, null, List.of(new ProbeTarget("127.0.0.1", 80, null)));
    }

    @Test
    void addAndFindRoundtrip() {
        TaskStore store = new TaskStore(TestFixtures.props());
        TaskState state = task("t1");
        store.add(state);
        assertThat(store.find("t1")).isSameAs(state);
        assertThat(store.find("missing")).isNull();
    }

    @Test
    void countActiveExcludesTerminal() {
        TaskStore store = new TaskStore(TestFixtures.props());
        TaskState running = task("a");
        running.markRunning();
        TaskState pending = task("b");
        TaskState finished = task("c");
        finished.finish(TaskStatus.SUCCEEDED, null);
        store.add(running);
        store.add(pending);
        store.add(finished);
        assertThat(store.countActive()).isEqualTo(2);
    }

    @Test
    void ttlExpiresFinishedTasksOnly() {
        AtomicLong offsetSeconds = new AtomicLong();
        ProbeProperties props = TestFixtures.props(3000, 10000, 200, 100, 10, 1000, false, 5.0, 10);
        TaskStore store = new TaskStore(props,
                () -> System.currentTimeMillis() / 1000.0 + offsetSeconds.get());

        TaskState finished = task("done");
        finished.finish(TaskStatus.SUCCEEDED, null);
        TaskState running = task("run");
        running.markRunning();
        store.add(finished);
        store.add(running);

        offsetSeconds.set(5);
        assertThat(store.find("done")).isNotNull();

        offsetSeconds.set(11);
        assertThat(store.find("done")).isNull();
        assertThat(store.find("run")).isNotNull();
    }

    @Test
    void capacityEvictsOldestFinishedOnly() {
        ProbeProperties props = TestFixtures.props(3000, 10000, 200, 100, 3600, 2, false, 5.0, 10);
        TaskStore store = new TaskStore(props);

        TaskState running = task("run");
        running.markRunning();
        TaskState first = task("f1");
        first.finish(TaskStatus.SUCCEEDED, null);
        TaskState second = task("f2");
        second.finish(TaskStatus.SUCCEEDED, null);

        store.add(running);
        store.add(first);
        store.add(second);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find("f1")).isNull();
        assertThat(store.find("f2")).isNotNull();
        assertThat(store.find("run")).isNotNull();
    }

    @Test
    void recentOrderedNewestFirst() {
        TaskStore store = new TaskStore(TestFixtures.props());
        TaskState older = task("old");
        older.markRunning();
        TaskState newer = task("new");
        newer.markRunning();
        store.add(older);
        store.add(newer);
        assertThat(store.recent(10)).extracting(TaskState::getId).containsExactly("new", "old");
    }
}
