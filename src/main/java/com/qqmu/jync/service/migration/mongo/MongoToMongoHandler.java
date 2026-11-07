package com.qqmu.jync.service.migration.mongo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.service.migration.MigrationCancellation;
import com.qqmu.jync.service.migration.MigrationGlob;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Check;
import com.qqmu.jync.service.migration.MigrationPrecheckResult.Level;
import com.qqmu.jync.service.migration.MigrationProgressListener;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationTypeHandler;
import com.qqmu.jync.service.migration.MigrationWriteMode;

/** MongoDB → MongoDB: collections, collection options, indexes and documents, {@code _id} kept. */
@Component
public class MongoToMongoHandler implements MigrationTypeHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MongoToMongoEngine engine;
    private final MongoMigrationGatewayFactory gatewayFactory;

    public MongoToMongoHandler(MongoMigrationGatewayFactory gatewayFactory) {
        this.gatewayFactory = gatewayFactory;
        this.engine = new MongoToMongoEngine(gatewayFactory);
    }

    @Override
    public MigrationType type() {
        return MigrationType.MONGODB_TO_MONGODB;
    }

    @Override
    public Set<MigrationConnectionKind> sourceKinds() {
        return Set.of(MigrationConnectionKind.MONGODB);
    }

    @Override
    public Set<MigrationConnectionKind> targetKinds() {
        return Set.of(MigrationConnectionKind.MONGODB);
    }

    @Override
    public JsonNode defaultOptions() {
        return MongoToMongoOptions.parse(JSON.createObjectNode()).toJson();
    }

    /** Accepts both canonical camelCase names and the form's {@code opt.*} prefix. */
    @Override
    public JsonNode parseOptions(ObjectNode formOptions) {
        ObjectNode merged = (ObjectNode) defaultOptions().deepCopy();
        if (formOptions != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = formOptions.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (key.startsWith("opt.")) {
                    key = key.substring("opt.".length());
                }
                if (merged.has(key)) {
                    merged.set(key, entry.getValue());
                }
            }
        }
        return MongoToMongoOptions.parse(merged).toJson();
    }

    @Override
    public MigrationPrecheckResult precheck(MigrationTask task, JsonNode optionsNode,
                                            MigrationConnection source,
                                            MigrationConnection target) {
        MongoToMongoOptions options = MongoToMongoOptions.parse(optionsNode);
        List<Check> checks = new ArrayList<>();

        MongoMigrationGateway sourceGateway;
        try {
            sourceGateway = gatewayFactory.open(source);
        } catch (RuntimeException e) {
            return result(checks, Level.ERROR, "migration.precheck.source.unreachable",
                    describe(e));
        }
        try (sourceGateway) {
            List<MongoCollectionInfo> discovered;
            try {
                sourceGateway.ping();
                discovered = sourceGateway.listCollections();
            } catch (RuntimeException e) {
                return result(checks, Level.ERROR, "migration.precheck.source.unreachable",
                        describe(e));
            }
            checks.add(new Check(Level.OK, "migration.precheck.source.reachable",
                    source.getName()));

            List<MongoCollectionInfo> collections = new ArrayList<>();
            for (MongoCollectionInfo info : discovered) {
                if (!MigrationGlob.matchesAny(info.name(), options.getCollectionsText())) {
                    continue;
                }
                if (MongoCollectionSupport.isSystem(info.name())
                        && !MigrationGlob.namesPrefix(options.getCollectionsText(), "system.")) {
                    continue;
                }
                collections.add(info);
            }
            if (collections.isEmpty()) {
                checks.add(new Check(Level.WARN, "migration.precheck.discovery.empty",
                        String.join(", ", options.patterns())));
                return new MigrationPrecheckResult(checks);
            }
            if (collections.size() > options.getMaxCollections()) {
                checks.add(new Check(Level.WARN, "migration.precheck.discovery.capped",
                        collections.size() + " > " + options.getMaxCollections()));
                collections = collections.subList(0, options.getMaxCollections());
            }

            MongoMigrationGateway targetGateway;
            try {
                targetGateway = gatewayFactory.open(target);
            } catch (RuntimeException e) {
                return result(checks, Level.ERROR, "migration.precheck.target.unreachable",
                        describe(e));
            }
            try (targetGateway) {
                for (MongoCollectionInfo info : collections) {
                    String targetName;
                    try {
                        targetName = options.targetName(info.name());
                    } catch (IllegalArgumentException e) {
                        checks.add(new Check(Level.ERROR,
                                "migration.precheck.index.targetName",
                                info.name() + " -> " + e.getMessage()));
                        continue;
                    }
                    if (!info.isCollection()) {
                        checks.add(new Check(Level.WARN, "migration.precheck.mongo.view",
                                info.name() + " (" + info.type() + ")"));
                        continue;
                    }
                    if (options.getWriteMode() == MigrationWriteMode.FAIL_IF_EXISTS) {
                        try {
                            if (targetGateway.collectionExists(targetName)) {
                                checks.add(new Check(Level.ERROR,
                                        "migration.precheck.index.targetExists", targetName));
                            }
                        } catch (RuntimeException e) {
                            checks.add(new Check(Level.ERROR,
                                    "migration.precheck.target.unreachable", describe(e)));
                            break;
                        }
                    }
                }
            }
        }
        return new MigrationPrecheckResult(checks);
    }

    @Override
    public MigrationRunResult run(MigrationTask task, JsonNode options,
                                  MigrationConnection source, MigrationConnection target,
                                  MigrationCancellation cancellation,
                                  MigrationProgressListener progress) {
        return engine.run(task, options, source, target, cancellation, progress);
    }

    private static MigrationPrecheckResult result(List<Check> checks, Level level,
                                                  String code, String detail) {
        checks.add(new Check(level, code, detail));
        return new MigrationPrecheckResult(checks);
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
