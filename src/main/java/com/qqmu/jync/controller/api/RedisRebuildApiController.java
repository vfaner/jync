package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.service.projection.redis.RedisRebuildService;

/**
 * The rebuild button on a Redis project's card: optionally sweep the project's key prefix, then
 * project every selected table again from a fresh anchor.
 *
 * <p>POST because it deletes cache keys and rewrites the projection; the blanket
 * {@code POST /** -> ADMIN} rule keeps it admin-only. Refusals leave through the shared
 * {@link ApiExceptionHandler}: a running project is a 409, a non-Redis project a 400.
 */
@RestController
@RequestMapping("/api/projects")
public class RedisRebuildApiController {

    private final RedisRebuildService service;

    public RedisRebuildApiController(RedisRebuildService service) {
        this.service = service;
    }

    @PostMapping("/{id}/redis-rebuild")
    public ResponseEntity<Map<String, Object>> rebuild(
            @PathVariable Long id,
            @RequestParam(name = "sweep", defaultValue = "false") boolean sweep) {
        RedisRebuildService.RebuildResult result = service.rebuild(id, sweep);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "msg.redis.rebuild.done");
        body.put("tables", result.tables());
        body.put("rows", result.rows());
        body.put("sweptKeys", result.sweptKeys());
        body.put("elapsedMs", result.elapsedMs());
        return ResponseEntity.ok(body);
    }
}
