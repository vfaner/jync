package com.qqmu.jync.service.etl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bson.Document;

import com.mongodb.MongoException;
import com.qqmu.jync.service.etl.mongo.MongoCursorHandle;
import com.qqmu.jync.service.etl.mongo.MongoResumeKeyCodec;
import com.qqmu.jync.service.etl.mongo.MongoValueRenderer;

/**
 * A driver cursor seen as an {@link EtlSourceCursor}. Values are rendered through the same
 * inferred column that the target table was created from, so a widened numeric column receives
 * the wider Java type and a text column receives readable text.
 */
final class MongoEtlSourceCursor implements EtlSourceCursor {

    private final MongoCursorHandle handle;
    private final List<EtlSourceColumn> columns;
    private final Set<String> knownFields = new HashSet<>();
    private final int resumeKeyIndex;
    private Document current;

    MongoEtlSourceCursor(MongoCursorHandle handle, List<EtlSourceColumn> columns,
                         int resumeKeyIndex) {
        this.handle = handle;
        this.columns = columns;
        this.resumeKeyIndex = resumeKeyIndex;
        for (EtlSourceColumn column : columns) {
            knownFields.add(column.name());
        }
    }

    @Override
    public List<EtlSourceColumn> columns() {
        return columns;
    }

    @Override
    public int resumeKeyIndex() {
        return resumeKeyIndex;
    }

    @Override
    public boolean next() {
        try {
            boolean more = handle.next();
            current = more ? handle.current() : null;
            if (current != null) {
                for (String field : current.keySet()) {
                    if (!knownFields.contains(field)) {
                        // A field the 200-document sample never saw has no target column.
                        // Skipping it silently lost data; fail with the field name instead.
                        throw new EtlSourceException("error.etl.mongo.field.unsampled", field);
                    }
                }
            }
            return more;
        } catch (MongoException e) {
            // The connection opened, so query() cannot classify this one: a server that goes
            // away mid-stream surfaces from the cursor. Left raw, the runner's RuntimeException
            // handler blames the program instead of the source the operator can act on.
            throw new EtlSourceException("etl.precheck.mongo.source.error",
                    e.getMessage() == null || e.getMessage().isBlank()
                            ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    @Override
    public Object getObject(int oneBasedIndex) {
        EtlSourceColumn column = columns.get(oneBasedIndex - 1);
        return MongoValueRenderer.render(column, current.get(column.name()));
    }

    @Override
    public String getString(int oneBasedIndex) {
        EtlSourceColumn column = columns.get(oneBasedIndex - 1);
        return MongoResumeKeyCodec.encode(current.get(column.name()));
    }

    @Override
    public void close() {
        handle.close();
    }
}
