package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncTaskRepository;
import com.qqmu.jync.service.CaptureStrategyParser;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Owns the CDC consumer threads of this node (task book B6/B7): one long-lived thread per
 * BINLOG_CDC project, guarded by the project's daemon lease.
 *
 * <p>Three entry points, all idempotent, all safe to call from any thread:
 * <ul>
 *   <li>{@link #ensureStarted} — UI start / bootstrap / reconcile / supervise takeover.</li>
 *   <li>{@link #ensureStopped} — UI stop / strategy switch / disable; joins the consumer
 *       so that when it returns, the old stream can no longer write (Review Focus #5).</li>
 *   <li>{@link #supervise} — the periodic convergence tick every node runs. This is the
 *       failover engine: a dead node's leases expire (≤ 30s), the next tick (≤ 10s) grabs
 *       them and launches consumers — total takeover &lt; 60s (acceptance B⑤).</li>
 * </ul>
 *
 * <p>A project parked in ERROR (fatal CDC failure, B7) is never restarted automatically —
 * on any node. The hat is lifted only by a human "rebuild baseline" (Task 17) going
 * through {@code taskStore.markCdcRunning}.
 */
@Slf4j
@Service
public class CdcConsumerManager {

    /**
     * Starts a consumer on its own thread and returns the STARTED thread so the manager
     * can join it later. Injectable for tests (a fake can hand back a thread that never
     * ran, or a recording spy); the default marks the thread daemon so a stuck consumer
     * can never block JVM exit.
     */
    @FunctionalInterface
    public interface ConsumerStarter {
        Thread start(Runnable consumer, String threadName);
    }

    private static final ConsumerStarter DEFAULT_STARTER = (consumer, threadName) -> {
        Thread t = new Thread(consumer, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    };

    /** One live consumer of this node: its stop handle, its thread (for join) and its stats. */
    private static final class Registration {
        final ManagedCdcConsumer consumer;
        final Thread thread;
        final CdcRuntimeStats stats;

        Registration(ManagedCdcConsumer consumer, Thread thread, CdcRuntimeStats stats) {
            this.consumer = consumer;
            this.thread = thread;
            this.stats = stats;
        }
    }

    private final ProjectRepository projectRepository;
    private final SyncTaskRepository taskRepository;
    private final SyncLockService lockService;
    private final SyncContextFactory contextFactory;
    private final BinlogClientFactory clientFactory;
    private final CdcPositionStore positionStore;
    private final CdcBaselineService baselineService;
    private final SyncTaskStore taskStore;
    private final SyncStateWriter stateWriter;
    private final DataSourceManager dataSourceManager;
    private final SyncProperties properties;
    private final ConsumerStarter starter;
    private final LogMinerSessionFactory logMinerSessionFactory;
    private final YStreamClientFactory yStreamClientFactory;
    private final YStreamServiceLifecycle yStreamLifecycle;

    /** projectId -> live consumer of THIS node. Absent = not owned here (other node or stopped). */
    private final Map<Long, Registration> registry = new ConcurrentHashMap<>();

    /**
     * projectId -> number of stops requested so far. A start acquires its lease and reads
     * this counter under the monitor, builds its context WITHOUT it (that step connects to
     * both endpoints and can wait out a connect timeout), then re-enters and refuses to
     * register when the counter moved: a stop that ran while the build was in flight has
     * already returned, so registering here would revive a consumer the caller declared
     * stopped. Same idea as the polling runner's stop epochs.
     */
    private final Map<Long, Long> stopGenerations = new ConcurrentHashMap<>();

    /**
     * Projects already warned about while parked in ERROR. {@link #supervise} re-asks every
     * tick, so without this the same WARN repeats every 10 s for as long as the project stays
     * parked (thousands of identical lines a day, hiding real ones). The warning marks a
     * state change, not a heartbeat: emit it once per park episode and drop the marker when
     * the ERROR hat is lifted, a consumer starts, or the project is stopped — a later park
     * is a new episode and warns again.
     */
    private final Set<Long> parkedWarned = ConcurrentHashMap.newKeySet();

    @Autowired
    public CdcConsumerManager(ProjectRepository projectRepository,
                              SyncTaskRepository taskRepository,
                              SyncLockService lockService,
                              SyncContextFactory contextFactory,
                              BinlogClientFactory clientFactory,
                              CdcPositionStore positionStore,
                              CdcBaselineService baselineService,
                              SyncTaskStore taskStore,
                              SyncStateWriter stateWriter,
                              DataSourceManager dataSourceManager,
                              SyncProperties properties,
                              LogMinerSessionFactory logMinerSessionFactory,
                              YStreamClientFactory yStreamClientFactory,
                              YStreamServiceLifecycle yStreamLifecycle) {
        this(projectRepository, taskRepository, lockService, contextFactory, clientFactory,
                positionStore, baselineService, taskStore, stateWriter, dataSourceManager,
                properties, DEFAULT_STARTER, logMinerSessionFactory, yStreamClientFactory,
                yStreamLifecycle);
    }

    public CdcConsumerManager(ProjectRepository projectRepository,
                              SyncTaskRepository taskRepository,
                              SyncLockService lockService,
                              SyncContextFactory contextFactory,
                              BinlogClientFactory clientFactory,
                              CdcPositionStore positionStore,
                              CdcBaselineService baselineService,
                              SyncTaskStore taskStore,
                              SyncStateWriter stateWriter,
                              DataSourceManager dataSourceManager,
                              SyncProperties properties,
                              ConsumerStarter starter,
                              LogMinerSessionFactory logMinerSessionFactory,
                              YStreamClientFactory yStreamClientFactory,
                              YStreamServiceLifecycle yStreamLifecycle) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.lockService = lockService;
        this.contextFactory = contextFactory;
        this.clientFactory = clientFactory;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.stateWriter = stateWriter;
        this.dataSourceManager = dataSourceManager;
        this.properties = properties;
        this.starter = starter;
        this.logMinerSessionFactory = logMinerSessionFactory;
        this.yStreamClientFactory = yStreamClientFactory;
        this.yStreamLifecycle = yStreamLifecycle;
    }

    /**
     * The capture strategy stored in the project's sync_config JSON. The parsing itself
     * lives in {@link CaptureStrategyParser} so CDC routing and the save/rebuild paths share
     * one read; corrupt or absent config means POLLING, so a broken row can never silently
     * claim a CDC consumer.
     */
    public CaptureStrategy strategyOf(Project project) {
        String json = project == null ? null : project.getSyncConfig();
        Long projectId = project == null ? null : project.getId();
        return CaptureStrategyParser.parse(json, projectId);
    }

    /**
     * Starts the consumer for one project on THIS node, if it should run here.
     *
     * <p>Skips, in order: already registered (idempotence — a dead thread is NOT pruned
     * here; only {@link #supervise} prunes, so a fatally parked project is never revived
     * by a UI click racing the tick); ERROR hat (B7 — a fatal CDC failure parks the
     * project until a human rebuilds the baseline; restarting it here would loop the
     * failure across every node); lease held elsewhere (another node owns the stream —
     * launching here would mean two writers).
     *
     * <p>The monitor covers only the decisions and the registration. The context build —
     * which connects to both endpoints — runs outside it, so a stop of ANY project does
     * not wait for a database that is slow or unreachable; the re-entry then checks
     * {@link #stopGenerations} so a stop that landed during the build wins.
     */
    public void ensureStarted(Project project) {
        Long projectId = project.getId();
        long stopGeneration;
        SyncLockService.LockHandle lock;
        synchronized (this) {
            if (registry.containsKey(projectId)) {
                // 幂等:已在本节点运行。注意这里只回答"有没有消费者在跑",不回答"跑的是
                // 不是该策略的那个":策略切换必须先经 ensureStopped 把旧消费者 join 掉
                // (ProjectService.applyStrategySwitch 两条分支都是这样),否则这条早退
                // 会让新引擎永远起不来,整条流还停在旧策略上。
                return;
            }
            if (hasErrorHat(projectId)) {
                if (parkedWarned.add(projectId)) {
                    log.warn("CDC project {} is parked in ERROR; not starting a consumer"
                            + " (rebuild the baseline to resume)", projectId);
                } else {
                    log.debug("CDC project {} is still parked in ERROR", projectId);
                }
                return;
            }
            parkedWarned.remove(projectId);                      // 帽子已摘:下一段停机会重新警告
            lock = lockService.tryAcquireForDaemon(
                    projectId, properties.getCdc().getLockTtlMs());
            if (lock == null) {
                log.info("CDC project {} is being consumed by another node; skipping here",
                        projectId);
                return;
            }
            stopGeneration = stopGenerations.getOrDefault(projectId, 0L);
        }
        try {
            // Building the context connects to both endpoints to resolve their default
            // schemas and can wait out a connect timeout. It must NOT run under the
            // monitor: ensureStopped is synchronized, so a UI stop would otherwise queue
            // behind an unreachable database.
            SyncContext ctx = contextFactory.build(project, lock.owner(), lock::renew);
            CaptureStrategy strategy = strategyOf(project);
            CdcRuntimeStats stats = new CdcRuntimeStats();
            ManagedCdcConsumer consumer = consumerFor(project, lock, ctx, strategy, stats);
            synchronized (this) {
                if (stopGenerations.getOrDefault(projectId, 0L) != stopGeneration) {
                    // A stop ran while we were connecting. It found nothing to stop and
                    // already returned, so registering now would revive what it stopped.
                    lock.close();
                    log.info("CDC project {} was stopped while its consumer was being"
                            + " prepared; not starting it", projectId);
                    return;
                }
                Thread thread = starter.start(consumer, "jync-cdc-" + projectId);
                registry.put(projectId, new Registration(consumer, thread, stats));
            }
            log.info("CDC consumer ({}) for project {} started on this node as {}",
                    strategy, projectId, lock.owner());
        } catch (RuntimeException e) {
            lock.close();                                        // 先放租约:他节点可接管
            // A stop that landed during the build already answered "should this run?";
            // it must not be overridden by an ERROR hat the failure would otherwise earn
            // (the next enable would find the project parked and silently keep it down).
            if (stopGenerationChanged(projectId, stopGeneration)) {
                log.info("CDC project {} was stopped while its consumer was being prepared;"
                        + " not parking the start failure", projectId);
            } else if (CdcFailureClassifier.classify(e) != CdcFailureClassifier.Kind.TRANSIENT) {
                // Only a failure a human must fix earns the ERROR hat. build() connects to
                // both endpoints to resolve their default schemas, so the common case here is
                // a TRANSIENT outage (the database simply is not up yet, or a network blip on
                // a supervise tick). Parking that would kill CDC on every node until somebody
                // rebuilds the baseline by hand — the exact failure mode the consumers avoid
                // by retrying TRANSIENT and parking only after a strike-out.
                parkQuietly(projectId, project.getName(), e);    // ERROR 帽子,防跨节点重启风暴
            } else {
                log.warn("CDC project {} could not start (transient, the next supervise tick"
                        + " will retry): {}", projectId, CdcFailureClassifier.describe(e));
            }
            throw e;
        }
    }

    /**
     * Whether an {@link #ensureStopped} for this project landed after the given generation
     * was read. Read under the monitor so it cannot interleave with the bump in
     * {@code ensureStopped} — otherwise a stop committed just after the check would still
     * let a start failure park the project.
     */
    private synchronized boolean stopGenerationChanged(Long projectId, long expected) {
        return stopGenerations.getOrDefault(projectId, 0L) != expected;
    }

    /**
     * One engine instance for the project's capture strategy.
     *
     * <p>Constructors only wire fields — no I/O — so this runs outside the monitor, right
     * before the registration half of {@link #ensureStarted}.
     */
    private ManagedCdcConsumer consumerFor(Project project, SyncLockService.LockHandle lock,
                                           SyncContext ctx, CaptureStrategy strategy,
                                           CdcRuntimeStats stats) {
        if (strategy == CaptureStrategy.LOGMINER_CDC) {
            return new LogMinerCdcConsumer(project, lock, ctx, positionStore,
                    baselineService, taskStore, stateWriter, dataSourceManager,
                    stats, properties, logMinerSessionFactory);
        }
        if (strategy == CaptureStrategy.PG_LOGICAL_CDC) {
            return new PgLogicalCdcConsumer(project, lock, ctx, positionStore,
                    baselineService, taskStore, stateWriter, dataSourceManager,
                    stats, properties);
        }
        if (strategy == CaptureStrategy.SQLSERVER_CDC) {
            return new SqlServerCdcConsumer(project, lock, ctx, positionStore,
                    baselineService, taskStore, stateWriter, dataSourceManager,
                    stats, properties);
        }
        if (strategy == CaptureStrategy.YSTREAM_CDC) {
            return new YStreamCdcConsumer(project, lock, ctx, positionStore,
                    baselineService, taskStore, stateWriter, dataSourceManager,
                    stats, properties, yStreamClientFactory, yStreamLifecycle);
        }
        return new CdcConsumer(project, lock, ctx, clientFactory,
                positionStore, baselineService, taskStore, stateWriter,
                dataSourceManager, stats, properties);
    }

    /**
     * Stops this node's consumer for the project and WAITS for it (Review Focus #5):
     * when this method returns, the old stream can no longer write to the target —
     * essential before a strategy switch starts a polling job for the same project.
     * The buffered (uncommitted-position) transaction replays after the next start,
     * so stopping mid-stream loses nothing.
     *
     * <p>Honesty clause (final review FIX 2): when the join budget expires, the thread is
     * interrupted and then re-joined for a short grace window. If it STILL lives, that is
     * logged at ERROR before returning — the caller (disable / rebaseline / strategy
     * switch) must be able to see that "stopped" is not yet trustworthy. The chunked
     * load's chunk-boundary interrupt check and the consumer's quiet stop-requested exit
     * make death within the grace window the expected case; the signature is unchanged.
     */
    public synchronized void ensureStopped(Long projectId) {
        // Bump FIRST, even when nothing is registered here: a start that is inside its
        // out-of-monitor context build reads this on re-entry and must not register.
        stopGenerations.merge(projectId, 1L, Long::sum);
        parkedWarned.remove(projectId);                          // 停/启边界:下次启动重新提示
        Registration registration = registry.remove(projectId);
        if (registration == null) {
            return;                                              // 本节点没有该消费者
        }
        registration.consumer.requestStop();
        try {
            registration.thread.join(properties.getShutdownWaitMs());
            if (registration.thread.isAlive()) {
                log.error("CDC consumer for project {} did not stop within {} ms;"
                                + " interrupting it (position may lag, replay will absorb)",
                        projectId, properties.getShutdownWaitMs());
                registration.thread.interrupt();
                confirmInterruptedStop(projectId, registration.thread);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registration.thread.interrupt();
            log.warn("Interrupted while waiting for the CDC consumer of project {} to stop",
                    projectId);
            confirmInterruptedStop(projectId, registration.thread);
        }
    }

    /** Post-interrupt grace window of {@link #ensureStopped} (final review FIX 2). */
    private static final long STOP_INTERRUPT_GRACE_MS = 5_000L;

    /**
     * Re-joins an interrupted consumer thread for a short grace window and says LOUDLY if
     * it survives: returning silently while the consumer may keep writing is exactly the
     * false "stopped" the callers of {@link #ensureStopped} must not believe.
     */
    private void confirmInterruptedStop(Long projectId, Thread thread) {
        try {
            thread.join(STOP_INTERRUPT_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.error("CDC consumer thread for project {} is STILL ALIVE {} ms after being"
                    + " interrupted; it may keep writing to the target until it reaches its"
                    + " next stop check — this node's stopped state for the project is NOT"
                    + " trustworthy", projectId, STOP_INTERRUPT_GRACE_MS);
        }
    }

    /** Runtime stats of this node's consumer for the project; null when not owned here. */
    public CdcRuntimeStats statsFor(Long projectId) {
        Registration registration = registry.get(projectId);
        return registration == null ? null : registration.stats;
    }

    /**
     * The periodic convergence tick — the failover engine (B6). Every node runs it:
     * <ol>
     *   <li>R2 prune: drop registrations whose thread has died (a fatal park or a quiet
     *       lease-lost exit leaves a corpse behind; without pruning, this node could
     *       never take the project back — B6 failback would stay broken forever. The
     *       ERROR hat in {@link #ensureStarted} still blocks reviving a parked project,
     *       so pruning cannot cause a restart storm.)</li>
     *   <li>start: every enabled BINLOG_CDC project without a live consumer here gets
     *       {@link #ensureStarted} — including the takeover path: the dead node's lease
     *       expires after ≤ lockTtlMs (30s) and the next tick (≤ 10s) acquires it.</li>
     *   <li>stop: registered consumers whose project is no longer enabled-CDC get
     *       {@link #ensureStopped} (disabled, deleted, or switched back to polling).</li>
     * </ol>
     *
     * <p>Deliberately NOT {@code synchronized}: every mutation is delegated to the
     * synchronized {@link #ensureStarted}/{@link #ensureStopped}, so holding the monitor
     * for the whole tick would only make a UI stop wait behind the rest of the tick's
     * projects — including their network connects.
     */
    @Scheduled(fixedDelayString = "${sync.cdc.supervise-interval-ms:10000}")
    public void supervise() {
        registry.entrySet().removeIf(entry -> {
            if (entry.getValue().thread.isAlive()) {
                return false;
            }
            log.info("Pruning dead CDC consumer registration for project {}", entry.getKey());
            return true;
        });

        Set<Long> wanted = new HashSet<>();
        boolean startedAny = false;
        try {
            for (Project project : projectRepository.findByEnabledTrue()) {
                if (!strategyOf(project).isLogCdc()) {
                    continue;
                }
                wanted.add(project.getId());
                if (!registry.containsKey(project.getId())) {
                    try {
                        ensureStarted(project);
                        startedAny = true;
                    } catch (RuntimeException e) {
                        log.error("CDC supervise could not start project {}: {}",
                                project.getId(), e.getMessage());
                    }
                }
            }
            if (startedAny) {
                // Not synchronized (see the javadoc): a stop can commit between the read
                // above and ensureStarted, which would leave that consumer running until
                // the next tick. Re-read once so the stop wins inside this same tick.
                wanted = cdcProjectIds();
            }
        } catch (RuntimeException e) {
            // A database hiccup makes `wanted` incomplete — converging stops against it
            // would kill healthy consumers. Abort the tick; the next one retries.
            log.warn("CDC supervise tick aborted, projects unreadable: {}", e.getMessage());
            return;
        }

        for (Long projectId : new ArrayList<>(registry.keySet())) {
            if (!wanted.contains(projectId)) {
                try {
                    ensureStopped(projectId);
                } catch (RuntimeException e) {
                    log.error("CDC supervise could not stop project {}: {}",
                            projectId, e.getMessage());
                }
            }
        }
    }

    /** Ids of the enabled projects that need a consumer, read fresh from the store. */
    private Set<Long> cdcProjectIds() {
        Set<Long> ids = new HashSet<>();
        for (Project project : projectRepository.findByEnabledTrue()) {
            if (strategyOf(project).isLogCdc()) {
                ids.add(project.getId());
            }
        }
        return ids;
    }

    /**
     * Graceful shutdown (B6): stop every consumer, current transaction first — finish or
     * drop it, persist the position, release the lease, exit. Semantics aligned with
     * BoundedQuartzShutdown: a shared total budget ({@code sync.shutdown-wait-ms}), each
     * join consuming what is left; a consumer that overruns the budget is reported and
     * interrupted — its position may lag, and at-least-once replay absorbs the overlap.
     * No {@code @PreDestroy}: this listener already covers the shutdown path, and a
     * second hook would double-stop.
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        if (registry.isEmpty()) {
            return;
        }
        log.info("Shutting down {} CDC consumer(s)", registry.size());
        for (Registration registration : registry.values()) {
            registration.consumer.requestStop();                 // 先全部发停,再逐个等
        }
        long deadline = System.currentTimeMillis() + properties.getShutdownWaitMs();
        for (Map.Entry<Long, Registration> entry : registry.entrySet()) {
            Thread thread = entry.getValue().thread;
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) {
                    thread.join(remaining);
                }
                if (thread.isAlive()) {
                    log.error("CDC consumer for project {} did not stop within the shutdown"
                            + " budget; position may lag, replay will absorb", entry.getKey());
                    thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                thread.interrupt();
                log.warn("Interrupted during CDC shutdown; remaining consumers may lag");
                break;
            }
        }
        registry.clear();
    }

    /** The B7 ERROR hat: a fatally parked project must not be auto-restarted anywhere. */
    private boolean hasErrorHat(Long projectId) {
        return taskRepository.findByProjectId(projectId)
                .map(task -> task.getStatus() == TaskStatus.ERROR)
                .orElse(false);
    }

    /** Startup-failure parking: ERROR hat + audit trail, both guarded (never mask the cause). */
    private void parkQuietly(Long projectId, String projectName, RuntimeException cause) {
        String detail = "CDC consumer failed to start: " + CdcFailureClassifier.describe(cause);
        try {
            taskStore.recordCdcFailure(projectId, CdcFailureClassifier.describe(cause));
        } catch (RuntimeException e) {
            log.warn("Could not record the CDC start failure of project {}: {}",
                    projectId, e.getMessage());
        }
        try {
            stateWriter.recordLog(projectId, ObjectType.PROJECT, projectName,
                    ChangeType.ERROR, detail, false, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the CDC start-failure log of project {}: {}",
                    projectId, e.getMessage());
        }
    }
}
