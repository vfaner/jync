package com.qqmu.jync.service.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The object reference at the start of a CREATE statement: type, optional schema, name.
 *
 * <p>An override is executed verbatim against the target, so the schema it names decides where
 * the object actually lands — schemas are server-level namespaces, not connection-scoped. On
 * MySQL, {@code CREATE PROCEDURE `src_schema`.`p`} creates {@code p} inside {@code src_schema}
 * on whichever server the target points at, and the operator looking at the target database
 * will not find it. Reading the reference back lets the save path refuse the mistake and the
 * apply path say what happened instead of surfacing the driver's "already exists".
 */
public final class DdlHeader {

    /**
     * Pulls the object type out of a CREATE header, tolerating the optional prefixes dialects
     * allow before it: OR REPLACE, and MySQL's DEFINER / ALGORITHM / SQL SECURITY clauses. It is
     * anchored at the start so a CREATE word buried in the body cannot satisfy it.
     */
    private static final Pattern CREATE_HEADER = Pattern.compile(
            "\\ACREATE\\s+(?:(?:OR\\s+REPLACE|OR\\s+ALTER)\\s+)?(?:(?:DEFINER\\s*=\\s*\\S+"
            + "|ALGORITHM\\s*=\\s*\\w+|SQL\\s+SECURITY\\s+\\w+"
            // Oracle puts these between OR REPLACE and the object kind; its own DDL export
            // (SQL Developer) writes "CREATE OR REPLACE FORCE VIEW", so rejecting them blocked
            // saving exactly the Oracle-authored overrides this feature exists for.
            + "|FORCE|NOFORCE|EDITIONABLE|NONEDITIONABLE|EDITIONING)\\s+)*"
            + "(?<type>VIEW|PROCEDURE|FUNCTION)\\b",
            Pattern.CASE_INSENSITIVE);

    private DdlHeader() {
    }

    /** The CREATE-header object type (VIEW/PROCEDURE/FUNCTION), or null when none is present. */
    public static String objectType(String sql) {
        String header = headerText(sql);
        Matcher matcher = CREATE_HEADER.matcher(header == null ? "" : header);
        return matcher.find() ? matcher.group("type").toUpperCase(Locale.ROOT) : null;
    }

    /**
     * True when the CREATE header replaces an existing object by itself (OR REPLACE / OR ALTER).
     * A plain CREATE fails with "already exists" once the object is present, so the override
     * path prepends a DROP in that case — otherwise the change never converges.
     */
    public static boolean replacesInPlace(String sql) {
        String header = headerText(sql);
        return header != null
                && header.matches("(?is)^CREATE\\s+OR\\s+(?:REPLACE|ALTER)\\s+.*");
    }

    /** The schema qualifier on the object, or null when the name is unqualified. */
    public static String schema(String sql) {
        List<String> path = objectPath(sql);
        return path.size() >= 2 ? path.get(path.size() - 2) : null;
    }

    /** The object name after the type, or null when no header is present. */
    public static String name(String sql) {
        List<String> path = objectPath(sql);
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    /**
     * Splits the (possibly qualified) name that follows the object type. Quotes are honoured so
     * a name containing a dot is not mistaken for a qualifier.
     */
    private static List<String> objectPath(String sql) {
        String header = headerText(sql);
        if (header == null) {
            return List.of();
        }
        Matcher matcher = CREATE_HEADER.matcher(header);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(3);
        int i = matcher.end();
        int n = header.length();
        while (true) {
            while (i < n && Character.isWhitespace(header.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            char c = header.charAt(i);
            String part;
            if (c == '`' || c == '"') {
                int end = i + 1;
                StringBuilder unescaped = new StringBuilder();
                while (end < n) {
                    char ch = header.charAt(end);
                    if (ch == c) {
                        // A doubled delimiter is one literal character, not the closing quote.
                        if (end + 1 < n && header.charAt(end + 1) == c) {
                            unescaped.append(c);
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    unescaped.append(ch);
                    end++;
                }
                if (end >= n) {
                    return List.of();
                }
                part = unescaped.toString();
                i = end + 1;
            } else if (c == '[') {
                int end = i + 1;
                StringBuilder unescaped = new StringBuilder();
                while (end < n) {
                    char ch = header.charAt(end);
                    if (ch == ']') {
                        if (end + 1 < n && header.charAt(end + 1) == ']') {
                            unescaped.append(']');
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    unescaped.append(ch);
                    end++;
                }
                if (end >= n) {
                    return List.of();
                }
                part = unescaped.toString();
                i = end + 1;
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(header.charAt(i))
                        && header.charAt(i) != '.' && header.charAt(i) != '('
                        && header.charAt(i) != ';') {
                    i++;
                }
                part = header.substring(start, i);
            }
            if (part.isEmpty()) {
                return List.of();
            }
            parts.add(part);
            // A path has at most database.schema.name; anything after that is the body.
            if (parts.size() == 3) {
                break;
            }
            while (i < n && Character.isWhitespace(header.charAt(i))) {
                i++;
            }
            if (i < n && header.charAt(i) == '.') {
                i++;
                continue;
            }
            break;
        }
        return parts;
    }

    /**
     * Skips whitespace and the leading block or line comments SSMS and SQL Developer put above
     * a generated script. Without this the anchored header never matched, so a legitimate
     * pasted override was refused as "no CREATE header".
     */
    private static String headerText(String sql) {
        if (sql == null) {
            return null;
        }
        int i = 0;
        int n = sql.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return null;
                }
                i = end + 2;
            } else if (i + 1 < n && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                if (end < 0) {
                    return null;
                }
                i = end + 1;
            } else {
                break;
            }
        }
        return sql.substring(i);
    }
}
