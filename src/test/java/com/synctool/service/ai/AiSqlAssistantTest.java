package com.synctool.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;
import com.synctool.model.DatabaseType;

/**
 * Reply parsing and prompt construction for {@link AiSqlAssistant}.
 *
 * <p>Every test here goes through a stub client, so nothing reaches the network. The point is
 * not whether a real model answers well — that cannot be asserted — but whether this class
 * survives the ways a model answers badly. A reply wrapped in a markdown fence, a reply that
 * ignores the JSON contract entirely, a reply truncated mid-fence by the token budget: each of
 * those would otherwise put garbage in the reviewer's editor or throw where a message belongs.
 */
class AiSqlAssistantTest {

    /** Returns a canned reply and records what it was asked. */
    private static class StubClient extends AiChatClient {
        private String reply = "{}";
        private boolean fail;
        private String failMessage = "HTTP 401";

        private String lastSystem;
        private String lastUser;
        private int lastMaxTokens;
        private int calls;

        @Override
        public ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                                   int maxTokens) {
            this.calls++;
            this.lastSystem = system;
            this.lastUser = user;
            this.lastMaxTokens = maxTokens;
            return fail
                    ? ChatResult.failure(failMessage, "http://stub", provider.getModel(), 5)
                    : ChatResult.success(reply, "served-model", "http://stub",
                            provider.getModel(), 5);
        }
    }

    private static final String ORACLE_BODY = """
            CREATE OR REPLACE PROCEDURE GET_TOTAL(p_id IN NUMBER) IS
            BEGIN
              SELECT NVL(SUM(amount), 0) INTO v_total FROM orders WHERE id = p_id;
            END;
            """;

    private StubClient client;
    private AiProviderService providerService;
    private AiSqlAssistant assistant;
    private AiProvider provider;

    @BeforeEach
    void setUp() {
        client = new StubClient();
        providerService = mock(AiProviderService.class);
        assistant = new AiSqlAssistant(providerService, client);

        provider = new AiProvider();
        provider.setId(1L);
        provider.setName("p");
        provider.setProtocol(AiProtocol.OPENAI);
        provider.setBaseUrl("http://stub");
        provider.setModel("some-model");
        provider.setMaxTokens(2048);

        when(providerService.activeProvider()).thenReturn(Optional.of(provider));
        when(providerService.decryptKey(provider)).thenReturn("sk-plain");
    }

    private AiSqlAssistant.Candidate draft() {
        return assistant.draft("PROCEDURE", "GET_TOTAL", ORACLE_BODY, null,
                DatabaseType.ORACLE, DatabaseType.MYSQL);
    }

    @Test
    @DisplayName("both products, the object name and the source body reach the prompt")
    void thePromptNamesBothProductsAndCarriesTheSource() {
        client.reply = "{\"sql\":\"CREATE PROCEDURE x() BEGIN END\",\"uncertainties\":[]}";
        draft();

        assertThat(client.lastUser)
                .contains("Oracle")
                .contains("MySQL")
                .contains("GET_TOTAL")
                .contains("SELECT NVL(SUM(amount), 0)");
        // Family drives the syntax, so the model is told it rather than left to infer it from
        // the product name -- 达梦 and Oracle need the same answer.
        assertThat(client.lastUser).contains("ORACLE").contains("MYSQL");
    }

    @Test
    @DisplayName("the system prompt forbids inventing schema and demands an uncertainty list")
    void theSystemPromptCarriesTheTwoRulesThatMatter() {
        client.reply = "{\"sql\":\"x\",\"uncertainties\":[]}";
        draft();

        // These two rules are why the whole workflow is trustworthy. If a refactor drops them
        // the feature still "works" and silently becomes much more dangerous.
        assertThat(client.lastSystem).contains("Never invent");
        assertThat(client.lastSystem).contains("uncertainties");
        assertThat(client.lastSystem).contains("NO_DATA_FOUND");
    }

    @Test
    @DisplayName("the mechanical attempt is offered as a starting point when it differs")
    void theMechanicalAttemptIsIncluded() {
        client.reply = "{\"sql\":\"x\",\"uncertainties\":[]}";
        assistant.draft("PROCEDURE", "GET_TOTAL", ORACLE_BODY,
                "CREATE PROCEDURE GET_TOTAL(p_id DECIMAL) BEGIN SELECT IFNULL(SUM(amount),0); END",
                DatabaseType.ORACLE, DatabaseType.MYSQL);

        assertThat(client.lastUser).contains("IFNULL").contains("starting point");
    }

    @Test
    @DisplayName("a mechanical attempt identical to the source is not pasted in twice")
    void anIdenticalMechanicalAttemptIsNotRepeated() {
        client.reply = "{\"sql\":\"x\",\"uncertainties\":[]}";
        assistant.draft("PROCEDURE", "GET_TOTAL", ORACLE_BODY, ORACLE_BODY,
                DatabaseType.ORACLE, DatabaseType.DM);

        // Same-family conversion is a passthrough. Sending the same text twice wastes budget
        // and invites the model to hunt for a difference that is not there.
        assertThat(client.lastUser).doesNotContain("starting point");
    }

    @Test
    @DisplayName("a bare JSON reply is parsed")
    void aBareJsonReplyIsParsed() {
        client.reply = "{\"sql\":\"CREATE PROCEDURE p() BEGIN SELECT 1; END\","
                + "\"uncertainties\":[\"Cursor loop rewritten as a WHILE loop\"]}";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).isEqualTo("CREATE PROCEDURE p() BEGIN SELECT 1; END");
        assertThat(candidate.getUncertainties())
                .containsExactly("Cursor loop rewritten as a WHILE loop");
        assertThat(candidate.getModel()).isEqualTo("served-model");
    }

    @Test
    @DisplayName("a JSON reply wrapped in a markdown fence is parsed")
    void aFencedJsonReplyIsParsed() {
        client.reply = """
                ```json
                {"sql": "CREATE PROCEDURE p() BEGIN SELECT 1; END", "uncertainties": ["a", "b"]}
                ```
                """;
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).isEqualTo("CREATE PROCEDURE p() BEGIN SELECT 1; END");
        assertThat(candidate.getUncertainties()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a reply that ignores the JSON contract is used as SQL but flagged")
    void anUnstructuredReplyIsUsedAndFlagged() {
        client.reply = """
                ```sql
                CREATE PROCEDURE GET_TOTAL(IN p_id DECIMAL) BEGIN SELECT 1; END
                ```
                """;
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).startsWith("CREATE PROCEDURE GET_TOTAL");
        // A model that ignored the output format may have ignored "do not invent" too, so the
        // reviewer is told rather than handed clean-looking SQL with no caveat.
        assertThat(candidate.getUncertainties()).containsExactly("error.ai.unstructuredReply");
    }

    @Test
    @DisplayName("an unterminated fence still yields the SQL inside it")
    void anUnterminatedFenceStillYieldsSql() {
        // The token budget ran out before the closing fence. Discarding the reply would waste
        // a paid call whose useful part arrived.
        client.reply = "```sql\nCREATE PROCEDURE p() BEGIN SELECT 1;";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).isEqualTo("CREATE PROCEDURE p() BEGIN SELECT 1;");
    }

    @Test
    @DisplayName("an empty sql field means the model declined, and its reasons are kept")
    void anEmptySqlFieldMeansDeclined() {
        client.reply = "{\"sql\":\"\",\"uncertainties\":"
                + "[\"Package-level state has no MySQL equivalent\"]}";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).isEqualTo("error.ai.declined");
        // The refusal reason is the whole value of the answer; losing it would make a declined
        // conversion indistinguishable from a network error.
        assertThat(candidate.getUncertainties())
                .containsExactly("Package-level state has no MySQL equivalent");
    }

    @Test
    @DisplayName("blank uncertainty entries are dropped rather than shown as empty bullets")
    void blankUncertaintyEntriesAreDropped() {
        client.reply = "{\"sql\":\"CREATE PROCEDURE p() BEGIN END\","
                + "\"uncertainties\":[\"real concern\",\"\",\"   \"]}";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.getUncertainties()).containsExactly("real concern");
    }

    @Test
    @DisplayName("an empty reply is a failure, not an empty candidate")
    void anEmptyReplyIsAFailure() {
        client.reply = "";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).isEqualTo("error.ai.emptyReply");
        // An empty candidate would silently blank the reviewer's editor.
        assertThat(candidate.getSql()).isEmpty();
    }

    @Test
    @DisplayName("a transport failure carries the endpoint's own message through")
    void aTransportFailureIsReported() {
        client.fail = true;
        client.failMessage = "HTTP 429 — rate limit exceeded";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).contains("429").contains("rate limit");
    }

    @Test
    @DisplayName("no enabled provider throws rather than looking like a conversion failure")
    void noEnabledProviderThrows() {
        when(providerService.activeProvider()).thenReturn(Optional.empty());

        // Reporting this as a Candidate failure would tell the user their procedure could not be
        // converted, when the truth is they never configured a model.
        assertThatThrownBy(this::draft)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ai.notAvailable");
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("a blank source body is rejected without spending a call")
    void aBlankSourceBodyIsRejectedLocally() {
        AiSqlAssistant.Candidate candidate = assistant.draft("PROCEDURE", "P", "   ", null,
                DatabaseType.ORACLE, DatabaseType.MYSQL);

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).isEqualTo("error.ai.noSourceBody");
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("the provider's Max Tokens is the reply budget")
    void theProvidersBudgetIsUsed() {
        client.reply = "{\"sql\":\"x\",\"uncertainties\":[]}";
        provider.setMaxTokens(8192);
        draft();

        assertThat(client.lastMaxTokens).isEqualTo(8192);
    }

    @Test
    @DisplayName("an unset Max Tokens falls back to a usable default, not zero")
    void anUnsetBudgetFallsBack() {
        client.reply = "{\"sql\":\"x\",\"uncertainties\":[]}";
        provider.setMaxTokens(null);
        draft();

        // A zero budget would make every request return an empty reply.
        assertThat(client.lastMaxTokens).isGreaterThan(0);
    }

    @Test
    @DisplayName("the decrypted key is what gets sent, never the stored ciphertext")
    void theDecryptedKeyIsUsed() {
        client.reply = "{\"sql\":\"x\",\"uncertainties\":[]}";
        provider.setApiKey("enc:ciphertext");
        draft();

        // Asserted through the service seam: decryptKey is the only sanctioned path, and a
        // refactor that read getApiKey() directly would send the ciphertext as a bearer token.
        org.mockito.Mockito.verify(providerService).decryptKey(provider);
    }
}
