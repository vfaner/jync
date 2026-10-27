package com.qqmu.jync.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.cdc.CdcNotHolderException;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcProbeResult;

/**
 * The JSON contract behind the CDC detail-page pair (task book B7/C3, Task 17):
 * the readiness probe GET and the one-click rebaseline POST.
 *
 * <p>搭法照抄 controller/api/ProjectApiControllerTest(@WebMvcTest + @WithMockUser(roles=
 * "ADMIN") + @MockBean service)。契约与 api 包的 {success,message} 约定不同:错误体是
 * {error:"&lt;message key&gt;"}(400),成功体带 ok:true——controller 自己 catch
 * IllegalArgumentException,不经过 ApiExceptionHandler(它的 basePackages 只盖
 * controller.api,而本控制器按任务书放在 controller 包)。
 */
@WebMvcTest(CdcApiController.class)
@WithMockUser(roles = "ADMIN")
class CdcApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private CdcOperationsService operations;

    /** 5 过 2 败(SERVER_ID 冲突 + 权限缺失),与 CdcOperationsServiceTest 同款样本。 */
    private static CdcProbeResult fivePassTwoFail() {
        return CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.VERSION, true, "MySQL 8.0.36"),
                CdcProbeResult.Check.of(CdcProbeResult.LOG_BIN, true, "ON"),
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_FORMAT, true, "ROW"),
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_ROW_IMAGE, true, "FULL"),
                CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, false,
                        "source server_id 612001 collides with the id Jync would present (612001)"
                                + " — change sync.cdc.server-id-offset"),
                CdcProbeResult.Check.of(CdcProbeResult.REPLICATION_GRANTS, false,
                        "missing grant(s): REPLICATION SLAVE"),
                CdcProbeResult.Check.of(CdcProbeResult.BINLOG_RETENTION, true,
                        "binlog_expire_logs_seconds=604800")));
    }

    @Test
    void probeReturnsJsonContract() throws Exception {
        when(operations.probeProject(1L)).thenReturn(fivePassTwoFail());

        mvc.perform(get("/api/projects/1/cdc-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.checks.length()").value(7))
                .andExpect(jsonPath("$.checks[0].name").value(CdcProbeResult.VERSION))
                .andExpect(jsonPath("$.checks[0].passed").value(true))
                .andExpect(jsonPath("$.checks[0].detail").value("MySQL 8.0.36"))
                // serverIdConflict 由 SERVER_ID 项推导:该项失败且 detail 指出与计划 id 相撞。
                .andExpect(jsonPath("$.serverIdConflict").value(true));
    }

    @Test
    void probeNonMysqlBecomes400() throws Exception {
        when(operations.probeProject(1L))
                .thenThrow(new IllegalArgumentException("cdc.probe.unsupported"));

        mvc.perform(get("/api/projects/1/cdc-probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("cdc.probe.unsupported"));
    }

    @Test
    void rebaselineReturnsOk() throws Exception {
        when(operations.rebaseline(1L)).thenReturn(true);

        mvc.perform(post("/api/projects/1/cdc-rebaseline").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("msg.cdc.rebaseline.done"));
        verify(operations).rebaseline(1L);
    }

    /**
     * 停用任务只重置基线、不启动消费者,接口必须换成「启用后重新全量」的提示,
     * 不能沿用运行中的「基线已重建」,否则用户又会以为全量已经在跑。
     */
    @Test
    void rebaselineOnDisabledTaskSaysPending() throws Exception {
        when(operations.rebaseline(1L)).thenReturn(false);

        mvc.perform(post("/api/projects/1/cdc-rebaseline").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("msg.cdc.rebaseline.pending"));
    }

    @Test
    void rebaselineOnPollingBecomes400() throws Exception {
        doThrow(new IllegalArgumentException("error.project.cdc.not.cdc"))
                .when(operations).rebaseline(1L);

        mvc.perform(post("/api/projects/1/cdc-rebaseline").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("error.project.cdc.not.cdc"));
    }

    /**
     * 终审 FIX 1:跨节点拒绝是「与当前持有者的冲突」,不是坏请求 → 409 + {error:key}。
     * 服务层在任何变更前抛出,所以被拒的重建不写任何 trail 行(由服务层测试钉住);
     * body 同时带 success:false/message,让 api-btn 通用分支把 key 本地化成 toast。
     */
    @Test
    void rebaselineOnForeignHolderBecomes409() throws Exception {
        doThrow(new CdcNotHolderException("cdc.rebaseline.not.holder"))
                .when(operations).rebaseline(1L);

        mvc.perform(post("/api/projects/1/cdc-rebaseline").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("cdc.rebaseline.not.holder"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("cdc.rebaseline.not.holder"))
                .andExpect(jsonPath("$.ok").doesNotExist());
    }
}
