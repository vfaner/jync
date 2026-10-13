package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * B3(DRY):把三处不同构的 containsIgnoreCase 私有实现(CdcEventApplier /
 * SyncConfig / DataSyncService)收敛为一个 null-safe 超集。契约取三处语义的并集:
 * haystack、needle、元素三者均可为 null 而不 NPE —— 顺带修复原 CdcEventApplier 与
 * DataSyncService「元素为 null 即 NPE」的潜在缺陷;真实调用点输入均非 null,行为不变。
 */
class CollectionUtilTest {

    @Test
    void findsMatchIgnoringCase() {
        assertThat(CollectionUtil.containsIgnoreCase(Arrays.asList("Foo", "BAR"), "foo")).isTrue();
        assertThat(CollectionUtil.containsIgnoreCase(Arrays.asList("Foo", "BAR"), "bar")).isTrue();
    }

    @Test
    void findsExactMatch() {
        assertThat(CollectionUtil.containsIgnoreCase(Arrays.asList("id", "name"), "id")).isTrue();
    }

    @Test
    void returnsFalseWhenNoElementMatches() {
        assertThat(CollectionUtil.containsIgnoreCase(Arrays.asList("id", "name"), "email"))
                .isFalse();
    }

    @Test
    void nullNeedleIsFalseNotAnError() {
        assertThat(CollectionUtil.containsIgnoreCase(Arrays.asList("id", "name"), null)).isFalse();
    }

    @Test
    void nullHaystackIsFalseNotAnError() {
        assertThat(CollectionUtil.containsIgnoreCase(null, "id")).isFalse();
    }

    @Test
    void nullElementsAreSkippedWithoutNpe() {
        List<String> withNull = Arrays.asList(null, "Name");
        assertThatCode(() -> CollectionUtil.containsIgnoreCase(withNull, "id"))
                .doesNotThrowAnyException();
        assertThat(CollectionUtil.containsIgnoreCase(withNull, "id")).isFalse();
        assertThat(CollectionUtil.containsIgnoreCase(withNull, "name")).isTrue();
    }

    @Test
    void worksForAnyCollectionType() {
        Set<String> set = new LinkedHashSet<>(Arrays.asList("Orders", "USERS"));
        assertThat(CollectionUtil.containsIgnoreCase(set, "users")).isTrue();
        assertThat(CollectionUtil.containsIgnoreCase(Collections.emptySet(), "users")).isFalse();
    }
}
