package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The change log's 详情 column follows the UI language.
 *
 * <p>{@code change_log.details} holds the English sentence the writer built at the time of the
 * event, so the column stayed English while every other column switched. The localizer maps the
 * known shapes back onto keys at render time: stored rows need no migration, historical entries
 * translate too, and a language switch applies to the whole page.
 *
 * <p>Anything it does not recognize is returned unchanged — a driver's message must reach the
 * operator exactly as the database said it, not be silently swallowed or half-translated.
 */
class ChangeLogDetailsLocalizerTest {

    private static final Locale ZH = Locale.SIMPLIFIED_CHINESE;

    private final ChangeLogDetailsLocalizer localizer = new ChangeLogDetailsLocalizer(messages());

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        // Mirrors spring.messages.fallback-to-system-locale=false: without it a JVM whose
        // default locale is zh_CN answers Locale.ENGLISH from the Chinese bundle, and the
        // English assertion would pass only on machines with an English default.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void cdcLifecycleEntriesAreTranslated() {
        assertThat(localizer.localize("CDC consumer started", ZH)).isEqualTo("CDC 消费器已启动");
        assertThat(localizer.localize("LogMiner consumer started", ZH))
                .isEqualTo("LogMiner 消费器已启动");
        assertThat(localizer.localize("PG consumer stopped", ZH)).isEqualTo("PG 消费器已停止");
        assertThat(localizer.localize("SQLServer CDC stopped: lease lost", ZH))
                .isEqualTo("SQLServer 消费器已停止（租约丢失）");
        assertThat(localizer.localize("YStream consumer stopped: lease lost", ZH))
                .isEqualTo("YStream 消费器已停止（租约丢失）");
        assertThat(localizer.localize("PG consumer failed: connection reset", ZH))
                .isEqualTo("PG 消费器失败：connection reset");
        assertThat(localizer.localize(
                        "CDC consumer failed to start: the source has no replication slot", ZH))
                .isEqualTo("CDC 消费器启动失败：the source has no replication slot");
        assertThat(localizer.localize("baseline rebuilt", ZH)).isEqualTo("基线已重建");
        assertThat(localizer.localize("baseline reset (task disabled)", ZH))
                .isEqualTo("基线已重置（任务已停用）");
    }

    @Test
    void cdcWindowCountsAreTranslated() {
        assertThat(localizer.localize("CDC window: 12 upserts, 3 deletes", ZH))
                .isEqualTo("CDC 窗口：写入 12 条，删除 3 条");
        assertThat(localizer.localize("CDC window: 0 upserts, 5 deletes (through 0:1:2048)", ZH))
                .isEqualTo("CDC 窗口：写入 0 条，删除 5 条（至 0:1:2048）");
    }

    @Test
    void tableCreationKeepsItsOptionalPhrases() {
        assertThat(localizer.localize("Created table customers", ZH)).isEqualTo("已创建表 customers");
        assertThat(localizer.localize("Table already present: customers", ZH))
                .isEqualTo("表已存在：customers");
        assertThat(localizer.localize("Created table customers with 3 index(es)", ZH))
                .isEqualTo("已创建表 customers，3 个索引");
        assertThat(localizer.localize("Created table customers, added column(s) region, code", ZH))
                .isEqualTo("已创建表 customers，新增列 region, code");
        assertThat(localizer.localize(
                        "Created table customers, added column(s) region with 2 index(es)", ZH))
                .isEqualTo("已创建表 customers，新增列 region，2 个索引");
        assertThat(localizer.localize("Created table orders, 2 secondary index(es) deferred until"
                + " the initial load completes", ZH))
                .isEqualTo("已创建表 orders，2 个二级索引待首次加载后创建");
    }

    @Test
    void structureChangesAreTranslated() {
        assertThat(localizer.localize("Dropped table orders", ZH)).isEqualTo("已删除表 orders");
        assertThat(localizer.localize("Added column region to customers", ZH))
                .isEqualTo("已为目标表 customers 新增列 region");
        assertThat(localizer.localize("Modified column region on customers", ZH))
                .isEqualTo("已修改目标表 customers 的列 region");
        assertThat(localizer.localize("Dropped column region from customers", ZH))
                .isEqualTo("已从目标表 customers 删除列 region");
        assertThat(localizer.localize("Added missing column(s) region, code to customers", ZH))
                .isEqualTo("已为目标表 customers 补齐缺失列 region, code");
        assertThat(localizer.localize("Dropped index idx_region", ZH))
                .isEqualTo("已删除索引 idx_region");
        assertThat(localizer.localize("Created index idx_region on customers", ZH))
                .isEqualTo("已在目标表 customers 创建索引 idx_region");
        assertThat(localizer.localize("Recreated index idx_region on customers", ZH))
                .isEqualTo("已在目标表 customers 重建索引 idx_region");
        assertThat(localizer.localize("Created previously-failed index idx_region on customers", ZH))
                .isEqualTo("已重建此前失败的索引 idx_region（表 customers）");
        assertThat(localizer.localize("Dropped view v_customers", ZH))
                .isEqualTo("已删除视图 v_customers");
        assertThat(localizer.localize("Created view v_customers", ZH))
                .isEqualTo("已创建视图 v_customers");
        assertThat(localizer.localize("Replaced view v_customers", ZH))
                .isEqualTo("已替换视图 v_customers");
        assertThat(localizer.localize("Created PROCEDURE p_recalc", ZH))
                .isEqualTo("已创建PROCEDURE p_recalc");
        assertThat(localizer.localize("Replaced FUNCTION f_tax", ZH))
                .isEqualTo("已替换FUNCTION f_tax");
    }

    @Test
    void skippedStructureChangesAreTranslated() {
        assertThat(localizer.localize("DROP not permitted by project settings", ZH))
                .isEqualTo("项目设置不允许删除，已跳过");
        assertThat(localizer.localize("Column DROP not permitted by project settings", ZH))
                .isEqualTo("项目设置不允许删除列，已跳过");
        assertThat(localizer.localize("Index DROP not permitted by project settings", ZH))
                .isEqualTo("项目设置不允许删除索引，已跳过");
        assertThat(localizer.localize("View DROP not permitted by project settings", ZH))
                .isEqualTo("项目设置不允许删除视图，已跳过");
        assertThat(localizer.localize("Routine DROP not permitted by project settings", ZH))
                .isEqualTo("项目设置不允许删除例程，已跳过");
        assertThat(localizer.localize("Target dialect cannot alter column types in place", ZH))
                .isEqualTo("目标库不支持原地修改列类型");
        assertThat(localizer.localize("Unsupported column change ALTER", ZH))
                .isEqualTo("不支持的列变更：ALTER");
        assertThat(localizer.localize("Unsupported object type INDEX", ZH))
                .isEqualTo("不支持的对象类型：INDEX");
        assertThat(localizer.localize(
                        "Index idx_region cannot be created on the target; data sync takes priority", ZH))
                .isEqualTo("索引 idx_region 无法在目标库创建，优先保证数据同步");
        assertThat(localizer.localize("No view definition available to apply", ZH))
                .isEqualTo("没有可用的视图定义");
        assertThat(localizer.localize("No routine body available to apply", ZH))
                .isEqualTo("没有可用的例程定义");
    }

    @Test
    void dataSyncEntriesAreTranslated() {
        assertThat(localizer.localize(
                        "Initial load: 4 row(s) inserted via identity strategy on created_at", ZH))
                .isEqualTo("首次加载：插入 4 行，使用 identity 策略，列 created_at");
        assertThat(localizer.localize(
                        "Incremental sync: 2 row(s) updated via timestamp strategy on updated_at", ZH))
                .isEqualTo("增量同步：更新 2 行，使用 timestamp 策略，列 updated_at");
        assertThat(localizer.localize(
                        "Initial load: 1 row(s) inserted (51 scanned, 50 already up to date)"
                                + " via identity strategy on created_at", ZH))
                .isEqualTo("首次加载：插入 1 行（扫描 51 行，其中 50 行已是最新），"
                        + "使用 identity 策略，列 created_at");
        assertThat(localizer.localize("Incremental sync: 3 row(s) inserted (8 scanned)"
                + " via identity strategy on id", ZH))
                .isEqualTo("增量同步：插入 3 行（扫描 8 行），使用 identity 策略，列 id");
        assertThat(localizer.localize("7 row(s) removed to match source", ZH))
                .isEqualTo("已按源库删除 7 行");
    }

    @Test
    void theDeletionSkipNoteIsTranslatedWithItsReason() {
        String raw = "Deletion sync for orders is skipped: it has no primary key, so rows cannot"
                + " be matched. Rows deleted at the source stay on the target.";
        assertThat(localizer.localize(raw, ZH)).contains("orders").contains("无主键，无法匹配行");

        assertThat(localizer.localize(
                        "Deletion sync for orders is skipped: it has about 900000 rows, over"
                                + " sync.full-compare-max-rows=500000. Rows deleted at the source"
                                + " stay on the target.", ZH))
                .contains("约 900000 行，超过 sync.full-compare-max-rows=500000");

        assertThat(localizer.localize(
                        "Deletion sync for orders is skipped: it has about 900000 rows, over"
                                + " sync.delete-compare-max-rows=200000. Rows deleted at the source"
                                + " stay on the target.", ZH))
                .contains("约 900000 行，超过 sync.delete-compare-max-rows=200000");
    }

    @Test
    void rowCountAuditEntriesAreTranslated() {
        assertThat(localizer.localize("Row count audit: target is missing 5 row(s) already marked"
                        + " as synced (source 100 vs target 95 at or below cursor 1700000000000)."
                        + " Cursor cleared; the table will be fully reloaded.", ZH))
                .isEqualTo("行数核对：目标库缺少 5 行已标记同步的行"
                        + "（源库 100 行，目标库 95 行，游标 1700000000000 及以下）。"
                        + "游标已清除，该表将全量重载。");
        assertThat(localizer.localize("Row count audit: target was missing 5 row(s) already"
                        + " marked as synced (source 100 vs target 95 at or below cursor 42)."
                        + " 5 row(s) re-copied by key; the cursor was kept and no reload was"
                        + " needed.", ZH))
                .isEqualTo("行数核对：目标库缺少 5 行已标记同步的行（源库 100 行，目标库 95 行，"
                        + "游标 42 及以下）。已按键重拷 5 行，游标保留，无需重载。");
    }

    @Test
    void schemaMonitorEntriesAreTranslated() {
        assertThat(localizer.localize("New table with 4 column(s)", ZH)).isEqualTo("新增表，共 4 列");
        assertThat(localizer.localize("Table comment changed", ZH)).isEqualTo("表注释已变更");
        assertThat(localizer.localize("Table no longer exists in source", ZH))
                .isEqualTo("源库中已不存在该表");
        assertThat(localizer.localize("New column region VARCHAR(64)", ZH))
                .isEqualTo("新增列 region VARCHAR(64)");
        assertThat(localizer.localize("Column region changed: VARCHAR(32) -> VARCHAR(64)", ZH))
                .isEqualTo("列 region 定义变更：VARCHAR(32) → VARCHAR(64)");
        assertThat(localizer.localize("Column region comment changed", ZH))
                .isEqualTo("列 region 的注释已变更");
        assertThat(localizer.localize("Column region removed from source", ZH))
                .isEqualTo("源库已删除列 region");
        assertThat(localizer.localize("New index UNIQUE INDEX uk_code (code, tenant)", ZH))
                .isEqualTo("新增索引 UNIQUE INDEX uk_code (code, tenant)");
        assertThat(localizer.localize("Index uk_code redefined", ZH)).isEqualTo("索引 uk_code 定义变更");
        assertThat(localizer.localize("Index uk_code removed", ZH)).isEqualTo("已删除索引 uk_code");
        assertThat(localizer.localize("View definition changed", ZH)).isEqualTo("视图定义已变更");
        assertThat(localizer.localize("View no longer exists in source", ZH))
                .isEqualTo("源库中已不存在该视图");
        assertThat(localizer.localize("Routine no longer exists in source", ZH))
                .isEqualTo("源库中已不存在该例程");
    }

    @Test
    void unrecognizedTextReachesTheOperatorVerbatim() {
        String driverError = "ORA-00942: table or view does not exist";
        assertThat(localizer.localize(driverError, ZH)).isEqualTo(driverError);
        assertThat(localizer.localize("", ZH)).isEmpty();
        assertThat(localizer.localize(null, ZH)).isNull();
        assertThat(localizer.localize(null)).isNull();
    }

    @Test
    void englishRendersTheEnglishWording() {
        assertThat(localizer.localize("CDC consumer started", Locale.ENGLISH))
                .isEqualTo("CDC consumer started");
        assertThat(localizer.localize("Initial load: 4 row(s) inserted via identity strategy",
                Locale.ENGLISH))
                .isEqualTo("Initial load: 4 row(s) inserted via identity strategy");
        assertThat(localizer.localize("Created table orders, 2 secondary index(es) deferred until"
                + " the initial load completes", Locale.ENGLISH))
                .isEqualTo("Created table orders, 2 secondary index(es) deferred until the"
                        + " initial load completes");
    }

    /** Every translated shape must be a real sentence in both bundles, not a leaked key. */
    @Test
    void noTranslationLeaksTheKeyItself() {
        assertThat(localizer.localize("CDC consumer started", ZH)).doesNotContain("log.detail");
        assertThat(localizer.localize("New table with 4 column(s)", ZH)).doesNotContain("log.detail");
        assertThat(localizer.localize("DROP not permitted by project settings", ZH))
                .doesNotContain("log.detail");
    }
}
