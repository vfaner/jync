package com.qqmu.jync.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一堆原始错误压成一句能读懂的话:失败了几项、什么原因、哪些表。
 *
 * <p>起因是一次实测:目标账号没有建表权限,三张表建表失败,而每条错误里都带着完整的
 * {@code CREATE TABLE} 语句({@link com.qqmu.jync.service.sync.DdlExecutor} 故意把失败的
 * 语句附在错误上,便于排查方言问题)。这些原文堆进摘要后,弹窗成屏 DDL,用户要按四次
 * 「确定」,还得自己从三段几乎相同的文本里认出"原来是权限不足";同一份摘要还会写进任务
 * 表,在项目列表里以一个小 chip 呈现。摘要要短,明细另有去处(变更日志)。
 *
 * <p>归约思路:错误文本一律是"对象名: 说明 -> 驱动消息"的形态,取最后一段作为原因,前缀
 * 像标识符时当作表名。原因相同的合并计数,表名最多点三个,原因最多列两类。
 */
final class ErrorDigest {

    /** 每条错误的形态:对象名(可带括号后缀,如 {@code LOGS (deletes)}) + ": " + 说明。 */
    private static final Pattern SUBJECT = Pattern.compile(
            "^([A-Za-z0-9_$.]{1,64})(?: \\([^)]{0,40}\\))?\\s*:\\s*(.*)$", Pattern.DOTALL);

    /** 驱动消息的挂载点,见 {@code DdlExecutor}:{@code "DDL failed: <sql> -> <message>"}。 */
    private static final String DRIVER_TAIL = "->";

    private static final int MAX_SUBJECTS = 3;
    private static final int MAX_GROUPS = 2;
    private static final int MAX_REASON_CHARS = 120;

    /** 错误文本里连一句说明都没有时的占位,不能压缩成空串。 */
    private static final String NO_DETAIL = "no detail recorded";

    private ErrorDigest() {
    }

    /** 摘要里那一句"{原因 (表名) | 原因 (表名) | +N more error(s)}"。 */
    static String of(List<String> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        for (String error : errors) {
            String[] parsed = parse(error);
            Group group = groups.computeIfAbsent(parsed[1], reason -> new Group(reason));
            group.errors++;
            if (parsed[0] != null) {
                group.subjects.add(parsed[0]);
            }
        }

        StringBuilder sb = new StringBuilder();
        int listed = 0;
        int covered = 0;
        for (Group group : groups.values()) {
            if (listed == MAX_GROUPS) {
                break;
            }
            if (listed > 0) {
                sb.append(" | ");
            }
            sb.append(group.reason).append(describe(group.subjects));
            covered += group.errors;
            listed++;
        }
        // 没列出来的原因只说还有几项,让用户知道摘要不是全部。
        if (covered < errors.size()) {
            sb.append(" | +").append(errors.size() - covered).append(" more error(s)");
        }
        return sb.toString();
    }

    /** @return {表名或 null, 原因},原因一定非空 */
    private static String[] parse(String error) {
        String text = error == null ? "" : error.trim();
        String subject = null;
        Matcher matcher = SUBJECT.matcher(text);
        if (matcher.matches()) {
            subject = matcher.group(1);
            text = matcher.group(2);
        }
        int arrow = text.lastIndexOf(DRIVER_TAIL);
        if (arrow >= 0) {
            // SQL 语句部分对用户没用(它就在变更日志里),驱动消息才是原因。
            text = text.substring(arrow + DRIVER_TAIL.length());
        }
        text = clip(text.replaceAll("\\s+", " ").trim());
        return new String[] {subject, text.isEmpty() ? NO_DETAIL : text};
    }

    private static String clip(String reason) {
        if (reason.length() <= MAX_REASON_CHARS) {
            return reason;
        }
        return reason.substring(0, MAX_REASON_CHARS).trim() + "...";
    }

    private static String describe(LinkedHashSet<String> subjects) {
        if (subjects.isEmpty()) {
            // 不是每类错误都挂得住表名(连接失败、游标冲突就没有),那时不硬套括号。
            return "";
        }
        List<String> names = new ArrayList<>(subjects);
        StringBuilder sb = new StringBuilder(" (");
        sb.append(String.join(", ", names.subList(0, Math.min(MAX_SUBJECTS, names.size()))));
        if (names.size() > MAX_SUBJECTS) {
            sb.append(", +").append(names.size() - MAX_SUBJECTS).append(" more");
        }
        return sb.append(')').toString();
    }

    private static final class Group {
        private final String reason;
        /** 保序去重:同一张表在一次运行里报两次也只点一次名。 */
        private final LinkedHashSet<String> subjects = new LinkedHashSet<>();
        private int errors;

        private Group(String reason) {
            this.reason = reason;
        }
    }
}
