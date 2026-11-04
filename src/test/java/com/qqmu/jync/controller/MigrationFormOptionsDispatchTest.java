package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.qqmu.jync.model.MigrationType;

/**
 * migration-form.html 按类型名 {@code th:switch} 分派参数片段；Thymeleaf 3.0 无法直接使用
 * handler 返回的片段表达式，所以新增迁移类型时模板必须同步补一个 {@code th:case}。
 *
 * <p>这里遍历所有已启用类型做校验。缺 case 时表单不会报错，只会静默缺少参数区——那种
 * 问题进到页面上才发现，太晚了。
 */
class MigrationFormOptionsDispatchTest {

    @Test
    void everyAvailableMigrationTypeHasAnOptionsCase() throws Exception {
        String template;
        try (InputStream in = new ClassPathResource("templates/migration-form.html")
                .getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (MigrationType type : MigrationType.values()) {
            if (!type.available()) {
                continue;
            }
            assertThat(template)
                    .as("migration-form.html 缺少 %s 的参数片段分派", type)
                    .contains("th:case=\"'" + type.name() + "'\"");
        }
    }
}
