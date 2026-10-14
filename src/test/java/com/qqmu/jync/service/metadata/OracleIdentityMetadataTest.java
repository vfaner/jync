package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The pre-12c auto-increment simulation — a BEFORE INSERT trigger that pulls a sequence into
 * {@code :NEW."COL"} — leaves no trace in JDBC's IS_AUTOINCREMENT. The reader reconstructs it
 * from trigger bodies; this pins the heuristic itself.
 */
class OracleIdentityMetadataTest {

    @Test
    void marksTheColumnFilledFromASequenceBeforeInsert() {
        String body = "BEGIN\n"
                + "  IF :NEW.ID IS NULL THEN\n"
                + "    SELECT SEQ_ORDERS.NEXTVAL INTO :NEW.ID FROM DUAL;\n"
                + "  END IF;\n"
                + "END;";

        Set<String> marked = OracleMetadataReader.identityColumnsFromTriggers(
                List.of("ID", "NAME"), List.of(body));

        assertThat(marked).containsExactly("ID");
    }

    @Test
    void recognisesQuotedNewColumnReferences() {
        String body = "begin if :new.\"order_id\" is null then "
                + "select seq.nextval into :new.\"order_id\" from dual; end if; end;";

        Set<String> marked = OracleMetadataReader.identityColumnsFromTriggers(
                List.of("ORDER_ID", "TOTAL"), List.of(body));

        assertThat(marked).containsExactly("ORDER_ID");
    }

    @Test
    void auditTriggersWithoutSequencesAreNotAutoIncrement() {
        String body = "BEGIN :NEW.UPDATED_AT := SYSTIMESTAMP; :NEW.UPDATED_BY := USER; END;";

        Set<String> marked = OracleMetadataReader.identityColumnsFromTriggers(
                List.of("ID", "UPDATED_AT", "UPDATED_BY"), List.of(body));

        assertThat(marked).isEmpty();
    }

    @Test
    void bodiesWithoutSequenceCallsAreIgnored() {
        Set<String> marked = OracleMetadataReader.identityColumnsFromTriggers(
                List.of("ID"), Arrays.asList("BEGIN :NEW.ID := 0; END;", null, ""));
        assertThat(marked).isEmpty();
    }
}
