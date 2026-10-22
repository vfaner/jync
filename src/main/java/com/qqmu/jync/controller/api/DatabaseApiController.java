package com.qqmu.jync.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.DatabaseConfigForm;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.connection.ConnectionTestService;
import com.qqmu.jync.service.connection.DriverLoader;
import com.qqmu.jync.service.connection.DriverPresence;

/** REST endpoints for connection testing and driver discovery. */
@RestController
@RequestMapping("/api/databases")
public class DatabaseApiController {

    private final DatabaseConfigService service;
    private final DriverLoader driverLoader;

    public DatabaseApiController(DatabaseConfigService service, DriverLoader driverLoader) {
        this.service = service;
        this.driverLoader = driverLoader;
    }

    /** Tests a saved connection. */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id) {
        return ResponseEntity.ok(describe(service.test(id)));
    }

    /**
     * Tests connection settings that have not been saved yet.
     *
     * <p>Lets the user validate a custom driver's jar path and class name before committing,
     * which is where most custom-database setup mistakes happen.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testTransient(
            @ModelAttribute DatabaseConfigForm form,
            @RequestParam(required = false) String rawPassword) {
        DatabaseConfig config = form.toEntity();
        Map<String, Object> body = describe(service.testTransient(config, rawPassword));
        body.put("jdbcUrl", service.previewUrl(config));
        return ResponseEntity.ok(body);
    }

    /** Driver class names advertised inside a jar, so the user need not know them. */
    @GetMapping("/discover-drivers")
    public ResponseEntity<Map<String, Object>> discoverDrivers(@RequestParam String jarPath) {
        List<String> drivers = driverLoader.discoverDriverClasses(jarPath);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("drivers", drivers);
        if (drivers.isEmpty()) {
            // A jar without the service descriptor is still usable; the class must be typed in.
            body.put("message", "msg.no.drivers.declared");
        }
        return ResponseEntity.ok(body);
    }

    /** Default port and URL template for a database type, to prefill the form. */
    @GetMapping("/type-defaults")
    public ResponseEntity<Map<String, Object>> typeDefaults(@RequestParam String type) {
        DatabaseType dbType = DatabaseType.fromName(type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("type", dbType.name());
        body.put("defaultPort", dbType.getDefaultPort());
        // SQL Server 命名实例没有固定端口：表单据此把端口留空，而不是自动填 1433。
        body.put("portOptional", dbType.allowsBlankPort());
        body.put("driverClass", dbType.getDriverClassName());
        body.put("urlTemplate", dbType.getUrlTemplate());
        body.put("custom", dbType.isCustom());
        // CUSTOM has no driver class of its own, so it is never "bundled".
        body.put("bundled", DriverPresence.isPresent(dbType.getDriverClassName()));
        // 商业信创预设才提供厂商 URL / jar / 驱动类覆盖;标准产品与 CUSTOM 不借此字段
        body.put("vendorDriver", dbType.supportsVendorDriver());

        return ResponseEntity.ok(body);
    }

    private Map<String, Object> describe(ConnectionTestService.TestResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.isSuccess());
        body.put("message", result.getMessage());
        body.put("productInfo", result.getProductInfo());
        body.put("driverInfo", result.getDriverInfo());
        body.put("catalog", result.getCatalog());
        body.put("schema", result.getSchema());
        body.put("elapsedMs", result.getElapsedMs());
        // CDC 探测结果仅 MySQL 源库存在;缺席时不写键,前端按无此区块渲染
        if (result.getCdcChecks() != null) {
            List<Map<String, Object>> checks = new ArrayList<>();
            for (CdcProbeResult.Check check : result.getCdcChecks()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", check.getName());
                item.put("passed", check.isPassed());
                item.put("detail", check.getDetail());
                checks.add(item);
            }
            body.put("cdcChecks", checks);
        }
        return body;
    }
}
