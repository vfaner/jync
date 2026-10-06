package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.MetadataSnapshot;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.MetadataSnapshotRepository;

/**
 * The snapshot service keeps an in-memory copy of the stored content hashes so an idle poll
 * cycle neither re-reads nor re-writes unchanged snapshots. The database stays the source of
 * truth, so the cache must never mask a change or a deletion — these tests pin the eviction
 * rules that guarantee it.
 *
 * <p>Each test uses its own project id: the cache outlives the per-test transaction rollback,
 * and a reused key would carry stale hashes across test methods.
 */
@DataJpaTest
@Import(MetadataSnapshotService.class)
class MetadataSnapshotServiceCacheTest {

    /** MetadataSnapshotService wants a mapper; the JPA slice does not provide one. */
    @TestConfiguration
    static class Json {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MetadataSnapshotService service;

    @Autowired
    private MetadataSnapshotRepository repository;

    private TableMeta meta(String columnName) {
        TableMeta table = new TableMeta();
        table.setName("T1");
        ColumnMeta column = new ColumnMeta();
        column.setName(columnName);
        column.setTypeName("VARCHAR");
        table.setColumns(List.of(column));
        return table;
    }

    @Test
    void aSavedSnapshotIsReadableBack() {
        service.save(911L, ObjectType.TABLE, "T1", meta("A"));

        Map<String, TableMeta> all = service.loadAll(911L, ObjectType.TABLE, TableMeta.class);

        assertThat(all).containsKey("T1");
        assertThat(all.get("T1").getColumns().get(0).getName()).isEqualTo("A");
    }

    @Test
    void reSavingUnchangedContentKeepsTheSnapshotReadable() {
        // 第二次 save 走缓存命中直接返回：读回的内容必须与第一次一致
        service.save(912L, ObjectType.TABLE, "T1", meta("A"));
        service.save(912L, ObjectType.TABLE, "T1", meta("A"));

        Map<String, TableMeta> all = service.loadAll(912L, ObjectType.TABLE, TableMeta.class);

        assertThat(all).containsKey("T1");
    }

    @Test
    void changedContentReplacesTheCachedSnapshot() {
        service.save(913L, ObjectType.TABLE, "T1", meta("A"));
        service.save(913L, ObjectType.TABLE, "T1", meta("B"));

        Map<String, TableMeta> all = service.loadAll(913L, ObjectType.TABLE, TableMeta.class);

        assertThat(all.get("T1").getColumns().get(0).getName()).isEqualTo("B");
    }

    @Test
    void deleteEvictsTheCacheSoTheSameContentCanBeSavedAgain() {
        service.save(914L, ObjectType.TABLE, "T1", meta("A"));
        service.delete(914L, ObjectType.TABLE, "T1");
        // 删完后重存同样的内容：若 delete 没有驱逐缓存，这次 save 会命中缓存跳过写库，
        // 快照将永远回不来 —— 这正是驱逐规则要防住的事故。
        service.save(914L, ObjectType.TABLE, "T1", meta("A"));

        assertThat(service.loadAll(914L, ObjectType.TABLE, TableMeta.class)).containsKey("T1");
    }

    @Test
    void deleteAllForProjectEvictsTheWholeProject() {
        service.save(915L, ObjectType.TABLE, "T1", meta("A"));
        service.deleteAllForProject(915L);
        service.save(915L, ObjectType.TABLE, "T1", meta("A"));

        assertThat(service.loadAll(915L, ObjectType.TABLE, TableMeta.class)).containsKey("T1");
    }

    @Test
    void aLegacyRowWithNullContentHashIsReadAndHealedWithoutAnNpe() throws Exception {
        // content_hash 是 v1.3.0 才有的列：更早版本写入的行升级后该列为 NULL。
        // 缓存命中比较若让 null 哈希参与 equals，这种行会让项目每一轮都 NPE，
        // 且重启无法自愈 —— loadAll 先于任何补写发生。
        MetadataSnapshot legacy = new MetadataSnapshot();
        legacy.setProjectId(916L);
        legacy.setObjectType(ObjectType.TABLE);
        legacy.setObjectName("T1");
        legacy.setSnapshotJson(new ObjectMapper().writeValueAsString(meta("A")));
        repository.saveAndFlush(legacy);

        assertThat(service.loadAll(916L, ObjectType.TABLE, TableMeta.class)).containsKey("T1");

        // 同内容 save：不得 NPE，且必须补齐哈希，让这行从此与普通行无异
        service.save(916L, ObjectType.TABLE, "T1", meta("A"));

        MetadataSnapshot healed = repository
                .findByProjectIdAndObjectTypeAndObjectName(916L, ObjectType.TABLE, "T1")
                .orElseThrow();
        assertThat(healed.getContentHash()).isNotNull();
        // 补齐后再读一轮，验证缓存命中路径同样安然无恙
        assertThat(service.loadAll(916L, ObjectType.TABLE, TableMeta.class)).containsKey("T1");
    }
}
