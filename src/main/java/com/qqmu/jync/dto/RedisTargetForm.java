package com.qqmu.jync.dto;

import com.qqmu.jync.model.RedisTarget;

import lombok.Getter;
import lombok.Setter;

/**
 * What the Redis target form may submit.
 *
 * <p>No password field: the secret travels as a separate {@code rawPassword} request parameter
 * and is never bound onto a form object the browser could round-trip back.
 */
@Getter
@Setter
public class RedisTargetForm {

    private Long id;
    private String name;
    private String host;
    private Integer port = 6379;
    private Integer databaseIndex = 0;
    private String username;
    private boolean tlsEnabled;
    private boolean verifyTls = true;
    private boolean clusterMode;
    private Integer connectTimeoutMs = 5000;
    private Integer commandTimeoutMs = 10000;
    private String description;

    public RedisTarget toEntity() {
        RedisTarget target = new RedisTarget();
        target.setId(id);
        target.setName(name);
        target.setHost(host);
        target.setPort(port == null ? 6379 : port);
        target.setDatabaseIndex(databaseIndex == null ? 0 : databaseIndex);
        target.setUsername(username);
        target.setTlsEnabled(tlsEnabled);
        target.setVerifyTls(verifyTls);
        target.setClusterMode(clusterMode);
        target.setConnectTimeoutMs(connectTimeoutMs == null ? 5000 : connectTimeoutMs);
        target.setCommandTimeoutMs(commandTimeoutMs == null ? 10000 : commandTimeoutMs);
        target.setDescription(description);
        return target;
    }

    public static RedisTargetForm from(RedisTarget target) {
        RedisTargetForm form = new RedisTargetForm();
        form.setId(target.getId());
        form.setName(target.getName());
        form.setHost(target.getHost());
        form.setPort(target.getPort());
        form.setDatabaseIndex(target.getDatabaseIndex());
        form.setUsername(target.getUsername());
        form.setTlsEnabled(target.isTlsEnabled());
        form.setVerifyTls(target.isVerifyTls());
        form.setClusterMode(target.isClusterMode());
        form.setConnectTimeoutMs(target.getConnectTimeoutMs());
        form.setCommandTimeoutMs(target.getCommandTimeoutMs());
        form.setDescription(target.getDescription());
        return form;
    }
}
