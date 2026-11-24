package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConnectionParamsTest {

    @Test
    void mergePreservesUntouchedParametersAndReplacesCaseInsensitively() {
        String merged = ConnectionParams.merge(
                "useSSL=false&CONNECTTIMEOUT=3000&keepAlive=true",
                Map.of("connectTimeout", "5000"), List.of());

        assertThat(merged).isEqualTo("useSSL=false&CONNECTTIMEOUT=5000&keepAlive=true");
    }

    @Test
    void mergeCanRemoveAndAppendAndReturnsNullWhenEverythingIsGone() {
        assertThat(ConnectionParams.merge("a=1&b=2", Map.of("c", "3"), List.of("a")))
                .isEqualTo("b=2&c=3");
        assertThat(ConnectionParams.merge("a=1", Map.of(), List.of("A"))).isNull();
        assertThat(ConnectionParams.merge(null, Map.of("socketTimeout", "60000"), List.of()))
                .isEqualTo("socketTimeout=60000");
    }

    @Test
    void aValueMayContainEqualsAndIsPreserved() {
        Map<String, String> parsed = ConnectionParams.parse("sql_mode=a=b&x=1");
        assertThat(parsed).containsEntry("sql_mode", "a=b");

        assertThat(ConnectionParams.merge("sql_mode=a=b", Map.of("x", "1"), List.of()))
                .isEqualTo("sql_mode=a=b&x=1");
    }

    @Test
    void unsafeKeysAndSeparatorValuesAreRefusedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> ConnectionParams.merge(null,
                Map.of("autoDeserialize", "true"), List.of()))
                .hasMessage("error.connection.url.unsafe");
        assertThatThrownBy(() -> ConnectionParams.merge(null,
                Map.of("socketTimeout", "1000&allowLoadLocalInfile=true"), List.of()))
                .hasMessage("error.connection.url.unsafe");
        assertThatThrownBy(() -> ConnectionParams.merge(null, Map.of("bad key", "1"), List.of()))
                .hasMessage("error.connection.url.unsafe");
        assertThatThrownBy(() -> ConnectionParams.merge(null, Map.of(), List.of("INIT")))
                .hasMessage("error.connection.url.unsafe");
        // A separator in the *key* would split one previewed parameter into two saved ones.
        assertThatThrownBy(() -> ConnectionParams.merge(null,
                Map.of("x&useSSL", "false"), List.of()))
                .hasMessage("error.connection.url.unsafe");
        assertThatThrownBy(() -> ConnectionParams.merge(null,
                Map.of("x=useSSL", "false"), List.of()))
                .hasMessage("error.connection.url.unsafe");
        assertThatThrownBy(() -> ConnectionParams.merge(null,
                Map.of("x;useSSL", "false"), List.of()))
                .hasMessage("error.connection.url.unsafe");
    }

    @Test
    void aLegacyBlockedPairDoesNotBlockAnUnrelatedEdit() {
        // Such a pair is dropped at URL build time; it must not make the stored string
        // impossible for the assistant to edit.
        assertThat(ConnectionParams.merge("INIT=runscript&useSSL=false",
                Map.of("connectTimeout", "5000"), List.of()))
                .isEqualTo("INIT=runscript&useSSL=false&connectTimeout=5000");
    }

    @Test
    void credentialLikeParametersAreRedactedForDisplay() {
        Map<String, String> redacted = ConnectionParams.redacted(ConnectionParams.parse(
                "connectTimeout=3000&password=hunter2&sslPassword=atLeastChangeIt"
                        + "&user=root&accessKey=AKIA&apiKey=x"));
        assertThat(redacted).containsEntry("connectTimeout", "3000")
                .containsEntry("password", "<redacted>")
                .containsEntry("sslPassword", "<redacted>")
                .containsEntry("user", "<redacted>")
                .containsEntry("accessKey", "<redacted>")
                .containsEntry("apiKey", "<redacted>")
                .doesNotContainValue("hunter2")
                .doesNotContainValue("AKIA");
    }

    @Test
    void lookupIsCaseInsensitiveAndAbsenceIsNull() {
        Map<String, String> parsed = ConnectionParams.parse("ConnectTimeout=3000");
        assertThat(ConnectionParams.lookup(parsed, "connecttimeout")).isEqualTo("3000");
        assertThat(ConnectionParams.lookup(parsed, "missing")).isNull();
    }
}
