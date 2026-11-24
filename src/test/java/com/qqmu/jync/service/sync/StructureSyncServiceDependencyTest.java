package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.TableMeta;

class StructureSyncServiceDependencyTest {

    private final StructureSyncService service = new StructureSyncService(null, null);

    private static TableMeta table(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);
        return table;
    }

    private static void references(TableMeta child, String parent) {
        TableMeta.ForeignKeyMeta fk = new TableMeta.ForeignKeyMeta();
        fk.setName("FK_" + child.getName());
        fk.setColumnName("PARENT_ID");
        fk.setReferencedTable(parent);
        fk.setReferencedColumn("ID");
        child.getForeignKeys().add(fk);
    }

    @Test
    void parentsFinishBeforeTheirChildren() {
        TableMeta items = table("ORDER_ITEMS");
        TableMeta orders = table("ORDERS");
        references(items, "orders");

        List<List<TableMeta>> levels = service.dependencyLevels(List.of(items, orders));

        assertThat(levels).hasSize(2);
        assertThat(levels.get(0)).extracting(TableMeta::getName).containsExactly("ORDERS");
        assertThat(levels.get(1)).extracting(TableMeta::getName).containsExactly("ORDER_ITEMS");
    }

    @Test
    void independentTablesShareOneWave() {
        List<List<TableMeta>> levels = service.dependencyLevels(
                List.of(table("A"), table("B"), table("C")));

        assertThat(levels).hasSize(1);
        assertThat(levels.get(0)).extracting(TableMeta::getName)
                .containsExactly("A", "B", "C");
    }

    @Test
    void anIncompleteCycleFallsBackToSequentialWaves() {
        TableMeta a = table("A");
        TableMeta b = table("B");
        references(a, "B");
        references(b, "A");

        List<List<TableMeta>> levels = service.dependencyLevels(List.of(a, b));

        assertThat(levels).hasSize(2);
        assertThat(levels).allSatisfy(level -> assertThat(level).hasSize(1));
    }
}
