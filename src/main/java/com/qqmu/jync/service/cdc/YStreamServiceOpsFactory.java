package com.qqmu.jync.service.cdc;

import java.sql.Connection;

/**
 * Builds the lifecycle port on one source connection.
 *
 * <p>A seam, not a layer: the consumer opens a source connection for the service
 * negotiation, and the lifecycle must be testable without a YashanDB source. Production
 * passes {@code JdbcYStreamServiceOps::new}; tests pass a fake.
 */
@FunctionalInterface
interface YStreamServiceOpsFactory {

    YStreamServiceOps create(Connection conn);
}
