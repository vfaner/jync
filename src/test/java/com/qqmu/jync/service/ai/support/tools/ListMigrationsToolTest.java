package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.migration.MigrationTaskService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assistant cannot diagnose or control a migration task it cannot name. This is the lookup
 * that turns "the ES migration stopped" into the numeric id every other migration tool takes.
 */
class ListMigrationsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));
    private final MigrationTaskRepository tasks = mock(MigrationTaskRepository.class);
    private final MigrationTaskService migrationTasks = mock(MigrationTaskService.class);

    private ListMigrationsTool tool() {
        return new ListMigrationsTool(tasks, migrationTasks);
    }

    private static MigrationTask task(long id, String name, MigrationType type) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setName(name);
        task.setType(type);
        task.setEnabled(true);
        task.setCronExpression("0 0 3 * * *");
        task.setOwnerUsername("admin");
        return task;
    }

    private JsonNode run(ObjectNode args) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));
    }

    @Test
    void listsTasksWithTheirTypeScheduleAndLastOutcome() throws Exception {
        MigrationTask es = task(2L, "es-orders", MigrationType.ES_TO_EASYSEARCH);
        es.setLastRunTime(Instant.parse("2026-11-20T02:00:00Z"));
        es.setLastRunSuccess(false);
        es.setLastRunResult("FAILED: target index exists");
        MigrationTask mongo = task(1L, "mongo-users", MigrationType.MONGODB_TO_MONGODB);
        when(tasks.findAllByOrderByIdDesc()).thenReturn(List.of(es, mongo));
        when(migrationTasks.isRunning(2L)).thenReturn(true);

        ObjectNode args = mapper.createObjectNode();
        JsonNode out = run(args);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).path("id").asLong()).isEqualTo(2L);
        assertThat(out.get(0).path("name").asString()).isEqualTo("es-orders");
        assertThat(out.get(0).path("type").asString()).isEqualTo("ES_TO_EASYSEARCH");
        assertThat(out.get(0).path("enabled").asBoolean()).isTrue();
        assertThat(out.get(0).path("running").asBoolean()).isTrue();
        assertThat(out.get(0).path("lastRunSuccess").asBoolean()).isFalse();
        assertThat(out.get(0).path("lastRunResult").asString())
                .isEqualTo("FAILED: target index exists");
        assertThat(out.get(1).path("running").asBoolean()).isFalse();
    }

    @Test
    void filtersByNameAndTypeSoTheModelResolvesTheRightId() throws Exception {
        when(tasks.findAllByOrderByIdDesc()).thenReturn(List.of(
                task(3L, "es-orders", MigrationType.ES_TO_EASYSEARCH),
                task(2L, "es-users", MigrationType.ES_TO_EASYSEARCH),
                task(1L, "mongo-orders", MigrationType.MONGODB_TO_MONGODB)));

        ObjectNode byName = mapper.createObjectNode();
        byName.put("query", "orders");
        JsonNode nameOut = run(byName);

        assertThat(nameOut).hasSize(2);
        assertThat(nameOut.get(0).path("id").asLong()).isEqualTo(3L);
        assertThat(nameOut.get(1).path("id").asLong()).isEqualTo(1L);

        ObjectNode byType = mapper.createObjectNode();
        byType.put("type", "MONGODB_TO_MONGODB");
        JsonNode typeOut = run(byType);

        assertThat(typeOut).hasSize(1);
        assertThat(typeOut.get(0).path("name").asString()).isEqualTo("mongo-orders");
    }

    @Test
    void capsTheRowCountAndClampsTheResultLine() throws Exception {
        List<MigrationTask> many = new ArrayList<>();
        for (int i = 30; i > 0; i--) {
            MigrationTask task = task(i, "task-" + i, MigrationType.ES_TO_EASYSEARCH);
            task.setLastRunResult("x".repeat(400));
            many.add(task);
        }
        when(tasks.findAllByOrderByIdDesc()).thenReturn(many);

        ObjectNode args = mapper.createObjectNode();
        JsonNode out = run(args);

        assertThat(out).hasSize(10);
        assertThat(out.get(0).path("id").asLong()).isEqualTo(30L);
        assertThat(out.get(0).path("lastRunResult").asString()).hasSize(160);
    }

    @Test
    void anUnknownTypeIsABadArgument() {
        ObjectNode args = mapper.createObjectNode();
        args.put("type", "ES_TO_NOWHERE");

        assertThatThrownBy(() -> tool().execute(args, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
    }

    @Test
    void theDescriptionNamesTheParametersTheSchemaDeclares() {
        assertThat(tool().description()).contains("query").contains("type");
        assertThat(tool().allowedKeys()).containsExactlyInAnyOrder("query", "type");
    }
}
