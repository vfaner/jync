package com.qqmu.jync.service.etl;

import java.util.List;

import org.bson.Document;

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
    private final int resumeKeyIndex;
    private Document current;

    MongoEtlSourceCursor(MongoCursorHandle handle, List<EtlSourceColumn> columns,
                         int resumeKeyIndex) {
        this.handle = handle;
        this.columns = columns;
        this.resumeKeyIndex = resumeKeyIndex;
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
        boolean more = handle.next();
        current = more ? handle.current() : null;
        return more;
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
