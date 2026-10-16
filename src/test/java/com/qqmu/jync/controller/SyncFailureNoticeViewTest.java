package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * 同步失败的提示语。
 *
 * <p>实测:目标账号没建表权限,三张表失败,弹窗第一句是「同步完成,但存在错误。」—— 用户
 * 的第一反应是"到底同步成功没有"。本轮跑完了不等于同步成功:失败的表在目标端一条数据
 * 都没有。所以这句话不许再出现,改用「本轮同步有失败项。」。
 *
 * <p>同时守住 app.js 取文案的那本字典:{@code msg.sync.*} 缺 key 时页面会内联输出
 * {@code ??msg.sync.xxx_zh_CN??},弹窗就显示 raw key(Windows 上曾经整页如此)。
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class SyncFailureNoticeViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String render() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(true);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        return mvc.perform(get("/projects/1").locale(Locale.SIMPLIFIED_CHINESE).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theToastBundleCarriesTheFailureKeys() throws Exception {
        String page = render();

        assertThat(page).contains("'msg.sync.completed.errors'", "'msg.sync.errors.hint'");
        assertThat(page).doesNotContain("??msg.sync");
    }

    @Test
    void aFailedRunIsNotAnnouncedAsACompletedOne() {
        // 文案断言读的是资源包而不是渲染出来的页面:Thymeleaf 内联 JS 时把非 ASCII 转成
        // unicode 转义序列,页面上找不到中文原文,拿页面断言等于假绿。
        assertThat(chinese("msg.sync.completed.errors")).isEqualTo("本轮同步有失败项。");
    }

    @Test
    void theFailureHintPointsAtWhereTheFullErrorsAre() {
        assertThat(chinese("msg.sync.errors.hint"))
                .as("摘要只剩一句话,完整报错(含 DDL 原文)得有明确的去处")
                .contains("变更日志");
    }

    private static String chinese(String key) {
        return ResourceBundle.getBundle("i18n.messages", Locale.SIMPLIFIED_CHINESE).getString(key);
    }
}
