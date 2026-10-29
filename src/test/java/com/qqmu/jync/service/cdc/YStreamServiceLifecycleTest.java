package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.qqmu.jync.service.cdc.YStreamServiceAdmin.Service;

/**
 * The spec's startup decision table, as a pure decision over a fake source.
 *
 * <p>The rules exist to answer one question safely: may this project resume from the stored
 * position, or must it rebuild? Resuming against a service that no longer covers the stored
 * position silently loses every change in between, so each branch below is asserted both by
 * the calls it makes and by the position the consumer is told to attach at.
 */
class YStreamServiceLifecycleTest {

    private static final long PROJECT = 7L;
    private static final List<String> TABLES = List.of("APP.ORDERS", "APP.ITEMS");
    private static final YStreamPosition ANCHOR = new YStreamPosition(100L, 0, 5L, 0, 0);

    private CdcPositionStore positionStore;
    private FakeOps ops;
    private YStreamServiceLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        positionStore = mock(CdcPositionStore.class);
        ops = new FakeOps();
        lifecycle = new YStreamServiceLifecycle(positionStore);
    }

    private static CdcPosition row(YStreamPosition lsn, boolean baselineDone) {
        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT);
        position.setLsn(lsn == null ? null : YStreamPositionCodec.encode(lsn));
        position.setBaselineDone(baselineDone);
        return position;
    }

    private YStreamServiceLifecycle.Outcome ensure() throws Exception {
        return lifecycle.ensure(PROJECT, TABLES, ops);
    }

    @Test
    void createsStartsAndAnchorsWhenTheSourceHasNeitherServiceNorPosition() throws Exception {
        when(positionStore.find(PROJECT)).thenReturn(Optional.empty());

        YStreamServiceLifecycle.Outcome outcome = ensure();

        assertThat(ops.calls).hasSize(3);
        assertThat(ops.calls.get(0)).matches("create:JYNC_7_[0-9A-F]{8}");
        assertThat(ops.calls.get(1)).isEqualTo("add:" + outcome.serviceName()
                + ":APP.ORDERS,APP.ITEMS");
        assertThat(ops.calls.get(2)).isEqualTo("start:" + outcome.serviceName());
        // 捕获起点由 START 钉住:锚点必须取自 START 后的 RESTART_POSITION。
        verify(positionStore).recordBaselineStartLsn(PROJECT, YStreamPositionCodec.encode(ANCHOR));
        assertThat(outcome.baselineRequired()).isTrue();
        assertThat(outcome.attachFrom()).isEqualTo(ANCHOR);
    }

    @Test
    void adoptsTheOnlyServiceAndRebuildsTheBaselineWhenItWasNotDone() throws Exception {
        ops.services = List.of(new Service(1L, "JYNC_7_0BADF00D", "STARTED", 90L,
                ANCHOR.text(), null));
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(row(ANCHOR, false)));

        YStreamServiceLifecycle.Outcome outcome = ensure();

        assertThat(ops.calls).containsExactly("add:JYNC_7_0BADF00D:APP.ORDERS,APP.ITEMS");
        assertThat(outcome.serviceName()).isEqualTo("JYNC_7_0BADF00D");
        assertThat(outcome.baselineRequired()).isTrue();
        assertThat(outcome.attachFrom()).isEqualTo(ANCHOR);
        verify(positionStore, never()).recordBaselineStartLsn(
                org.mockito.ArgumentMatchers.eq(PROJECT),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void attachesToTheStoredPositionWhenTheBaselineIsDone() throws Exception {
        ops.services = List.of(new Service(1L, "JYNC_7_0BADF00D", "STARTED", 90L,
                ANCHOR.text(), null));
        ops.captured.addAll(TABLES);
        YStreamPosition advanced = new YStreamPosition(250L, 1, 77L, 3, 4);
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(row(advanced, true)));

        YStreamServiceLifecycle.Outcome outcome = ensure();

        assertThat(ops.calls).isEmpty();
        assertThat(outcome.baselineRequired()).isFalse();
        assertThat(outcome.attachFrom()).isEqualTo(advanced);
    }

    @Test
    void addsOnlyTheTablesTheServiceDoesNotCaptureYet() throws Exception {
        ops.services = List.of(new Service(1L, "JYNC_7_0BADF00D", "STARTED", 90L,
                ANCHOR.text(), null));
        ops.captured.add("APP.ORDERS");
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(row(ANCHOR, true)));

        ensure();

        assertThat(ops.calls).containsExactly("add:JYNC_7_0BADF00D:APP.ITEMS");
    }

    @Test
    void dropsEveryServiceAndStartsOverWhenMoreThanOneIsLeftBehind() throws Exception {
        ops.services = List.of(
                new Service(1L, "JYNC_7_0BADF00D", "STARTED", 90L, ANCHOR.text(), null),
                new Service(2L, "JYNC_7_11112222", "CREATED", 95L, null, null));
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(row(ANCHOR, true)));

        YStreamServiceLifecycle.Outcome outcome = ensure();

        assertThat(ops.calls).contains("drop:JYNC_7_0BADF00D", "drop:JYNC_7_11112222");
        // 旧位点属于被丢弃的服务,不能沿用到新服务上。
        verify(positionStore).delete(PROJECT);
        assertThat(outcome.serviceName()).isNotIn("JYNC_7_0BADF00D", "JYNC_7_11112222");
        assertThat(outcome.baselineRequired()).isTrue();
    }

    @Test
    void dropsTheServiceAndStartsOverWhenThePositionRowIsGone() throws Exception {
        ops.services = List.of(new Service(1L, "JYNC_7_0BADF00D", "STARTED", 90L,
                ANCHOR.text(), null));
        when(positionStore.find(PROJECT)).thenReturn(Optional.empty());

        YStreamServiceLifecycle.Outcome outcome = ensure();

        assertThat(ops.calls.get(0)).isEqualTo("drop:JYNC_7_0BADF00D");
        assertThat(outcome.baselineRequired()).isTrue();
        assertThat(ops.calls).anyMatch(call -> call.startsWith("create:"));
    }

    @Test
    void discardsThePositionAndStartsOverWhenNoServiceRemains() throws Exception {
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(row(ANCHOR, true)));

        YStreamServiceLifecycle.Outcome outcome = ensure();

        verify(positionStore).delete(PROJECT);
        assertThat(outcome.baselineRequired()).isTrue();
        assertThat(ops.calls).anyMatch(call -> call.startsWith("create:"));
    }

    @Test
    void restartsAServiceLeftRunningWithoutAConsumer() throws Exception {
        ops.services = List.of(new Service(1L, "JYNC_7_0BADF00D", "RUNNING", 90L,
                ANCHOR.text(), null));
        ops.captured.addAll(TABLES);
        YStreamPosition advanced = new YStreamPosition(250L, 1, 77L, 3, 4);
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(row(advanced, true)));

        YStreamServiceLifecycle.Outcome outcome = ensure();

        // 复位不改变 START_SCN/RESTART_POSITION,所以位点仍然可用。
        assertThat(ops.calls).containsExactly("stop:JYNC_7_0BADF00D", "start:JYNC_7_0BADF00D");
        assertThat(outcome.attachFrom()).isEqualTo(advanced);
    }

    @Test
    void refusesToStartWithoutASelectedTable() {
        assertThatThrownBy(() -> lifecycle.ensure(PROJECT, List.of(), ops))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
        assertThat(ops.calls).isEmpty();
    }

    @Test
    void anUnreadableStoredPositionIsDiscardedRatherThanTrusted() {
        CdcPosition garbage = row(null, true);
        garbage.setLsn("not-a-position");
        when(positionStore.find(PROJECT)).thenReturn(Optional.of(garbage));

        assertThatCode(this::ensure).doesNotThrowAnyException();

        verify(positionStore).delete(PROJECT);
    }

    /** A scripted source: records the calls and keeps the service list in sync with them. */
    private static final class FakeOps implements YStreamServiceOps {

        final List<String> calls = new ArrayList<>();
        final Set<String> captured = new LinkedHashSet<>();
        List<Service> services = new ArrayList<>();
        /** What START leaves in {@code RESTART_POSITION} — the baseline anchor. */
        String restartPositionAfterStart = ANCHOR.text();
        private long nextServerId = 10L;

        @Override
        public List<Service> services(long projectId) {
            return List.copyOf(services);
        }

        @Override
        public void create(String serviceName) {
            calls.add("create:" + serviceName);
            List<Service> next = new ArrayList<>(services);
            next.add(new Service(nextServerId++, serviceName, "CREATED", 0L, null, null));
            services = next;
        }

        @Override
        public void addTables(String serviceName, List<String> qualifiedTables) {
            calls.add("add:" + serviceName + ":" + String.join(",", qualifiedTables));
            captured.addAll(qualifiedTables);
        }

        @Override
        public void start(String serviceName) {
            calls.add("start:" + serviceName);
            replace(serviceName, "STARTED", restartPositionAfterStart);
        }

        @Override
        public void stop(String serviceName) {
            calls.add("stop:" + serviceName);
            replace(serviceName, "CREATED", null);
        }

        @Override
        public void drop(Service service) {
            calls.add("drop:" + service.name());
            List<Service> next = new ArrayList<>(services);
            next.removeIf(s -> s.name().equals(service.name()));
            services = next;
        }

        @Override
        public Set<String> captured(String serviceName) {
            return captured;
        }

        private void replace(String serviceName, String status, String restartPosition) {
            List<Service> next = new ArrayList<>();
            for (Service s : services) {
                next.add(s.name().equals(serviceName)
                        ? new Service(s.serverId(), s.name(), status, s.startScn(),
                                restartPosition, s.error())
                        : s);
            }
            services = next;
        }
    }
}
