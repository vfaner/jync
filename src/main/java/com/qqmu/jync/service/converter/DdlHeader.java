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
            "\\ACREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:DEFINER\\s*=\\s*\\S+"
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
        Matcher matcher = CREATE_HEADER.matcher(sql == null ? "" : sql);
        return matcher.find() ? matcher.group("type").toUpperCase(Locale.ROOT) : null;
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
        if (sql == null) {
            return List.of();
        }
        Matcher matcher = CREATE_HEADER.matcher(sql);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(2);
        int i = matcher.end();
        int n = sql.length();
        while (true) {
            while (i < n && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            char c = sql.charAt(i);
            String part;
            if (c == '`' || c == '"') {
                int end = sql.indexOf(c, i + 1);
                if (end < 0) {
                    return List.of();
                }
                part = sql.substring(i + 1, end);
                i = end + 1;
            } else if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                if (end < 0) {
                    return List.of();
                }
                part = sql.substring(i + 1, end);
                i = end + 1;
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(sql.charAt(i))
                        && sql.charAt(i) != '.' && sql.charAt(i) != '('
                        && sql.charAt(i) != ';') {
                    i++;
                }
                part = sql.substring(start, i);
            }
            if (part.isEmpty()) {
                return List.of();
            }
            parts.add(part);
            // A path has at most schema.name; anything after that is the parameter list or body.
            if (parts.size() == 2) {
                break;
            }
            while (i < n && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i < n && sql.charAt(i) == '.') {
                i++;
                continue;
            }
            break;
        }
        return parts;
    }
}
