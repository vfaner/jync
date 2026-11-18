package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;

/**
 * A batch's members belong to whoever owns the batch.
 *
 * <p>Lives in this package because {@code buildMembers} is package-visible: the direct check is
 * cheaper and more precise than driving the whole creation path through a catalog stub.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:batchowner;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/batchowner-logs/jync.log"
})
class EtlBatchMemberOwnershipTest {

    @Autowired
    private EtlBatchService service;

    @Test
    void buildMembersStampsTheBatchOwnerOnEveryTask() {
        EtlBatch batch = new EtlBatch();
        batch.setId(7L);
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        batch.setOwnerUsername("alice");
        DatabaseConfig source = new DatabaseConfig();
        source.setName("member-src");
        source.setType(DatabaseType.MYSQL);

        List<EtlTask> members = service.buildMembers(
                batch, List.of("orders", "users"), "public", source);

        assertThat(members).extracting(EtlTask::getOwnerUsername).containsOnly("alice");
    }
}
