package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code @Lob String} that also declares a {@code @Column} must declare a length.
 *
 * <p>Hibernate 7's MySQL DDL type selection reads the length: {@code @Lob} with an explicit
 * {@code @Column} and no {@code length} maps to TINYTEXT (255 bytes), so a DDL statement or an
 * error detail longer than that fails the insert with MySQL error 1406 — no column can hold it
 * and Hibernate's auto-update never widens it. The wide mapping appears only when the column
 * says {@code length = Integer.MAX_VALUE}; {@code @Lob} on its own already maps to LONGTEXT.
 */
class EntityLobColumnsDeclareALengthTest {

    @Test
    @DisplayName("every model LOB column declares an explicit length")
    void everyModelLobColumnDeclaresItsLength() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/qqmu/jync/model"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String problem : lobColumnsWithoutLength(source)) {
                    problems.add(file.getFileName() + " " + problem);
                }
            }
        }

        assertThat(problems)
                .as("a TINYTEXT column truncates the write at 255 bytes (MySQL 1406)")
                .isEmpty();
    }

    /** A scan that cannot fail is not a guard: prove it both reports and ignores. */
    @Test
    @DisplayName("the scan reports a LOB column without a length and ignores the safe shapes")
    void theScanCanFail() {
        String source = """
                class A {
                    @Lob
                    @Column(name = "too_small")
                    private String tooSmall;

                    @Lob
                    @Enumerated(EnumType.STRING)
                    @Column(name = "wide", length = Integer.MAX_VALUE)
                    private String wide;

                    @Lob
                    private String defaultsToTheWideMapping;

                    @Column(name = "plain", length = 32)
                    private String notLob;
                }
                """;

        assertThat(lobColumnsWithoutLength(source))
                .containsExactly("line 2: private String tooSmall;");
    }

    /** Reports each {@code @Lob} column that declares a {@code @Column} without a length. */
    static List<String> lobColumnsWithoutLength(String source) {
        List<String> problems = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("@Lob")) {
                continue;
            }
            StringBuilder annotations = new StringBuilder();
            String field = "";
            int j = i + 1;
            while (j < lines.length) {
                String line = lines[j].trim();
                if (line.startsWith("private ") || line.startsWith("protected ")
                        || line.startsWith("public ")) {
                    field = line;
                    break;
                }
                annotations.append(line).append(' ');
                j++;
            }
            String block = annotations.toString();
            if (!block.contains("@Column")) {
                // No @Column at all: Hibernate's default LOB mapping is already the wide type.
                continue;
            }
            String arguments = columnArguments(block);
            if (arguments.contains("length") || arguments.contains("columnDefinition")) {
                continue;
            }
            problems.add("line " + (i + 1) + ": " + field);
        }
        return problems;
    }

    /** The argument list of the {@code @Column} annotation in a joined annotation block. */
    private static String columnArguments(String annotations) {
        int at = annotations.indexOf("@Column");
        int open = annotations.indexOf('(', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < annotations.length(); i++) {
            char c = annotations.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return annotations.substring(open + 1, i);
            }
        }
        return annotations.substring(open + 1);
    }
}
