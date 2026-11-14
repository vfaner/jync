package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * Namespace ownership on a shared Redis.
 *
 * <p>Two projects are safe only when their prefixes name disjoint key namespaces. Equality is
 * the obvious half; containment is the half that costs keys, because a rebuild's sweep deletes
 * everything matching {@code <prefix>*} — a broader prefix owns every narrower one nested under
 * it.
 */
class RedisProjectionPolicyTest {

    private static final long TARGET_ID = 9L;

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final SyncContextFactory factory = mock(SyncContextFactory.class);
    private final RedisProjectionPolicy policy = new RedisProjectionPolicy(
            mock(RedisTargetRepository.class), projects, factory);

    @Test
    void aPrefixNestedUnderAnotherProjectsPrefixIsAConflict() {
        // The sweep of "jync:" matches "jync:2:orders:1": a rebuild of project 1 with the sweep
        // option would delete every key project 2 owns.
        Project project = project(1L, "jync:");
        Project sibling = project(2L, "jync:2:");
        when(projects.findByTargetRedisId(TARGET_ID)).thenReturn(List.of(project, sibling));

        assertThat(policy.conflictingProject(project, "jync:")).isSameAs(sibling);
    }

    @Test
    void anAncestorPrefixClaimedByAnotherProjectIsAConflict() {
        // The same overlap seen from the shorter side.
        Project project = project(2L, "jync:2:");
        Project sibling = project(1L, "jync:");
        when(projects.findByTargetRedisId(TARGET_ID)).thenReturn(List.of(project, sibling));

        assertThat(policy.conflictingProject(project, "jync:2:")).isSameAs(sibling);
    }

    @Test
    void siblingPrefixesThatOnlyShareASpellingDoNotConflict() {
        // "jync:1:" is not a prefix of "jync:12:": neither project's sweep matches the other's
        // keys, so the default per-project namespaces stay usable side by side.
        Project project = project(1L, "jync:1:");
        Project sibling = project(12L, "jync:12:");
        when(projects.findByTargetRedisId(TARGET_ID)).thenReturn(List.of(project, sibling));

        assertThat(policy.conflictingProject(project, "jync:1:")).isNull();
    }

    private Project project(long id, String prefix) {
        Project project = new Project();
        project.setId(id);
        project.setName("project-" + id);
        project.setTargetRedisId(TARGET_ID);
        SyncConfig config = new SyncConfig();
        RedisProjectionConfig projection = new RedisProjectionConfig();
        projection.setKeyPrefix(prefix);
        config.setRedisProjection(projection);
        when(factory.parseConfig(project)).thenReturn(config);
        return project;
    }
}
