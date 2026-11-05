package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Rows written before Redis targets existed carry no {@code target_kind}. Reading such a
 * project must keep every existing code path on the JDBC branch rather than throwing or
 * taking the Redis branch by accident.
 */
class ProjectTargetKindTest {

    @Test
    void aProjectWithoutTargetKindReadsAsJdbc() {
        Project project = new Project();

        assertThat(project.getTargetKind()).isNull();
        assertThat(project.getTargetKindOrDefault()).isEqualTo(SyncTargetKind.JDBC);
    }

    @Test
    void aRedisProjectKeepsItsDeclaredKind() {
        Project project = new Project();
        project.setTargetKind(SyncTargetKind.REDIS);

        assertThat(project.getTargetKindOrDefault()).isEqualTo(SyncTargetKind.REDIS);
    }
}
