package com.qqmu.jync.service.ai.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Product-usage documentation bundled with the jar.
 *
 * <p>Loaded once at startup. Retrieval is deliberately dependency-free: Chinese bigrams plus
 * lowercase ASCII words, scored by set intersection with a double weight on the section title.
 * A tokenizer or embedding model would be more precise, but it would also add a dependency and
 * an offline-availability problem to a feature whose whole point is working inside an intranet.
 */
@Component
@Slf4j
public class SupportKnowledgeBase {

    public record Section(String title, String body) {
    }

    /**
     * One section's body may not flood the prompt; a retrieved excerpt is capped here.
     *
     * <p>600 was too short for the sections that matter during an incident: the CDC
     * baseline section reaches its "does a rebuild delete rows?" answer past character 600,
     * so the model received the setup and none of the conclusion. 1000 fixed that section
     * but not the assistant section, whose English text puts its later bullets past 1000.
     * 4500 covers the longest surviving section ("What the AI operations assistant can do",
     * which now includes the log and instance-code tools); the Chinese variant was split
     * into shorter sections instead. The total across sections is still bounded by
     * {@link #TOTAL_EXCERPT_BUDGET}, well under the registry's 8 KB result cap.
     */
    private static final int MAX_EXCERPT = 4500;

    /**
     * Total body characters across one search result. Three maximal sections would be 9 KB,
     * which the tool registry would silently cut at 8 KB — losing the last section entirely
     * instead of shortening each. The budget gives the first (best-scoring) sections most of
     * the space and drops the tail section rather than truncating the JSON.
     */
    private static final int TOTAL_EXCERPT_BUDGET = 6000;

    private static final int MAX_TOP_K = 5;

    private final Map<String, List<Section>> byLanguage = new LinkedHashMap<>();

    public SupportKnowledgeBase() {
        load("zh", "ai/support-knowledge.zh.md");
        load("en", "ai/support-knowledge.en.md");
    }

    private void load(String language, String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("Support knowledge file {} is missing", path);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            byLanguage.put(language, split(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            log.warn("Could not read support knowledge file {}: {}", path, e.toString());
        }
    }

    /** Splits on '## ' headings; the preamble above the first heading is not an answer. */
    static List<Section> split(String markdown) {
        List<Section> sections = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (title != null) {
                    sections.add(new Section(title, body.toString().trim()));
                }
                title = line.substring(3).trim();
                body.setLength(0);
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) {
            sections.add(new Section(title, body.toString().trim()));
        }
        return sections;
    }

    /**
     * Returns the best-matching sections for a question, most relevant first.
     *
     * <p>Empty means "the documentation does not cover this" — deliberately not "return
     * everything", which would burn the answer budget on irrelevant text.
     */
    public List<Section> search(String language, String query, int topK) {
        String lang = language != null && language.startsWith("en") ? "en" : "zh";
        List<Section> sections = byLanguage.get(lang);
        if (sections == null || sections.isEmpty()) {
            sections = byLanguage.getOrDefault("zh", List.of());
        }
        Set<String> wanted = features(query);
        if (wanted.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(topK, MAX_TOP_K));

        List<Scored> hits = new ArrayList<>();
        for (Section section : sections) {
            int titleScore = intersection(wanted, features(section.title()));
            int bodyScore = intersection(wanted, features(section.body()));
            int score = titleScore * 2 + bodyScore;
            if (score > 0) {
                hits.add(new Scored(section, score));
            }
        }
        hits.sort(Comparator.comparingInt(Scored::score).reversed());

        List<Section> result = new ArrayList<>();
        int budget = TOTAL_EXCERPT_BUDGET;
        for (Scored hit : hits) {
            if (result.size() >= limit || budget <= 0) {
                break;
            }
            String body = hit.section().body();
            int cap = Math.min(MAX_EXCERPT, budget);
            if (body.length() > cap) {
                body = cutAtLine(body, cap) + "…";
            }
            result.add(new Section(hit.section().title(), body));
            budget -= Math.min(body.length(), cap + 1);
        }
        return result;
    }

    /** Cut on a line boundary so a bullet is never cut mid-sentence; fall back near the cap. */
    private static String cutAtLine(String body, int cap) {
        int cut = body.lastIndexOf('\n', cap);
        return cut > cap / 2 ? body.substring(0, cut) : body.substring(0, cap);
    }

    private record Scored(Section section, int score) {
    }

    private static int intersection(Set<String> a, Set<String> b) {
        int count = 0;
        for (String token : a) {
            if (b.contains(token)) {
                count++;
            }
        }
        return count;
    }

    /** Chinese bigrams plus ASCII words of two or more letters. */
    static Set<String> features(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lower.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (word.length() >= 2 && word.chars().allMatch(c -> c < 128)) {
                out.add(word);
            }
        }
        for (String run : lower.replaceAll("[^\\u4e00-\\u9fff]", " ").split("\\s+")) {
            for (int i = 0; i + 1 < run.length(); i++) {
                out.add(run.substring(i, i + 2));
            }
        }
        return out;
    }
}
