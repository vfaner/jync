package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.LogCapture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * 源库对象列举失败时,页面只显示一句通用提示,真正的失败原因(ORA 错误码、解密失败等)
 * 只能靠服务端日志。旧实现把这条日志放在 DEBUG、且只打印 {@code e.toString()}:
 * 生产默认 INFO 级别下它根本不会出现,用户和运维都无从判断是权限、网络还是密钥问题。
 *
 * <p>本测试钉住:失败必须留下一条 WARN,并且异常链(含被包装的 SQLException)完整进日志。
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectDetailListingFailureLogTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    @Test
    void listingFailureIsLoggedAtWarnWithItsCauseChain() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenThrow(new IllegalStateException(
                "error.source.objects.read.failed",
                new SQLException("ORA-00942: table or view does not exist")));

        ListAppender<ILoggingEvent> logs = LogCapture.attach(ProjectController.class);
        try {
            mvc.perform(get("/projects/1")).andExpect(status().isOk());
        } finally {
            LogCapture.detach(ProjectController.class, logs);
        }

        List<String> warnings = LogCapture.messagesAt(Level.WARN, logs);
        assertThat(warnings)
                .as("source-object listing failures must be visible at the default INFO log level")
                .anyMatch(message -> message.contains("source objects"));
        ILoggingEvent event = logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("source objects"))
                .findFirst()
                .orElseThrow();
        assertThat(rootCauseMessage(event.getThrowableProxy()))
                .as("the log must carry the driver's own message, not just the message key")
                .contains("ORA-00942");
    }

    private static String rootCauseMessage(IThrowableProxy throwable) {
        IThrowableProxy current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "" : String.valueOf(current.getMessage());
    }
}
