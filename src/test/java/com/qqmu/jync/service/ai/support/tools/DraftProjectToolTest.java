package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportDraftStore;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.ObjectMapper;

class DraftProjectToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseConfigService configs = mock(DatabaseConfigService.class);
    private final RedisTargetRepository redis = mock(RedisTargetRepository.class);
    private final SupportDraftStore store = new SupportDraftStore();
    private final AccessGuard actor = mock(AccessGuard.class);
    private final ToolContext ctx = new ToolContext(actor);

    private DraftProjectTool tool() {
        return new DraftProjectTool(configs, redis, store);
    }

    private DatabaseConfig db(long id, ConnectionRole role, DatabaseType type, String owner) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName("conn-" + id);
        c.setRole(role);
        c.setType(type);
        c.setOwnerUsername(owner);
        return c;
    }

    @Test
    void aValidRequestProducesATokenAndStoredProject() throws Exception {
        when(actor.username()).thenReturn("alice");
        when(actor.canEdit("alice")).thenReturn(true);
        when(configs.findById(1L)).thenReturn(Optional.of(
                db(1L, ConnectionRole.SOURCE, DatabaseType.MYSQL, "alice")));
        when(configs.findById(2L)).thenReturn(Optional.of(
                db(2L, ConnectionRole.TARGET, DatabaseType.KINGBASE, "alice")));

        var args = mapper.createObjectNode();
        args.put("name", "orders-sync");
        args.put("sourceDbId", 1);
        args.put("targetDbId", 2);
        var out = mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));

        assertThat(out.path("ok").asBoolean()).isTrue();
        String token = out.path("token").asString();
        var draft = store.take(token, "alice");
        assertThat(draft).isPresent();
        assertThat(draft.get().project().getSourceDbId()).isEqualTo(1L);
        assertThat(draft.get().project().getTargetDbId()).isEqualTo(2L);
        assertThat(draft.get().project().getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void aForeignConnectionIsRefused() throws Exception {
        when(actor.username()).thenReturn("alice");
        when(configs.findById(1L)).thenReturn(Optional.of(
                db(1L, ConnectionRole.SOURCE, DatabaseType.MYSQL, "bob")));
        when(actor.canEdit("bob")).thenReturn(false);

        var args = mapper.createObjectNode();
        args.put("name", "x");
        args.put("sourceDbId", 1);
        args.put("targetDbId", 1);
        var out = mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));

        assertThat(out.path("ok").asBoolean()).isFalse();
        assertThat(out.toString()).contains("error.ownership.reference");
    }

    @Test
    void aWrongRoleConnectionIsRefused() throws Exception {
        when(actor.username()).thenReturn("alice");
        when(actor.canEdit("alice")).thenReturn(true);
        when(configs.findById(1L)).thenReturn(Optional.of(
                db(1L, ConnectionRole.TARGET, DatabaseType.MYSQL, "alice")));

        var args = mapper.createObjectNode();
        args.put("name", "x");
        args.put("sourceDbId", 1);
        args.put("targetDbId", 1);
        var out = mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));

        assertThat(out.path("ok").asBoolean()).isFalse();
    }

    @Test
    void aRedisTargetUsesTheRedisId() throws Exception {
        when(actor.username()).thenReturn("alice");
        when(actor.canEdit("alice")).thenReturn(true);
        when(configs.findById(1L)).thenReturn(Optional.of(
                db(1L, ConnectionRole.SOURCE, DatabaseType.MYSQL, "alice")));
        RedisTarget target = new RedisTarget();
        target.setId(9L);
        target.setName("cache-prod");
        target.setOwnerUsername("alice");
        when(redis.findById(9L)).thenReturn(Optional.of(target));

        var args = mapper.createObjectNode();
        args.put("name", "cache-orders");
        args.put("sourceDbId", 1);
        args.put("targetKind", "REDIS");
        args.put("targetRedisId", 9);
        var out = mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));

        assertThat(out.path("ok").asBoolean()).isTrue();
        assertThat(store.take(out.path("token").asString(), "alice").get()
                .project().getTargetRedisId()).isEqualTo(9L);
    }
}
