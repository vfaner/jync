package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.cdc.YStreamEvent.Kind;
import com.qqmu.jync.service.cdc.YStreamEvent.Operation;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.sics.ystream.exception.YstreamUnsupportedOperationException;
import com.sics.ystream.metadata.Column;
import com.sics.ystream.metadata.ColumnType;
import com.sics.ystream.metadata.TableId;
import com.sics.ystream.metadata.TableType;
import com.sics.ystream.result.DmlType;
import com.sics.ystream.result.LcrType;
import com.sics.ystream.result.LogPosition;
import com.sics.ystream.result.Position;
import com.sics.ystream.result.SystemChangeNumber;
import com.sics.ystream.result.XactType;
import com.sics.ystream.result.YstreamColumn;
import com.sics.ystream.result.YstreamColumns;
import com.sics.ystream.result.YstreamDdlInterface;
import com.sics.ystream.result.YstreamDmlInterface;
import com.sics.ystream.result.YstreamLcrInterface;
import com.sics.ystream.result.YstreamXactInterface;
import com.yashandb.protocol.accessor.Accessor;

/**
 * The one place the third-party LCR types are interpreted. Everything downstream works on
 * {@link YStreamEvent}, so a wrong accessor here (old vs new values on a DELETE, a dropped
 * NULL column) would only show up as wrong rows in a live target.
 */
class YstreamLcrAdapterTest {

    private static final TableId ORDERS = new TableId("APP", "ORDERS", 1L, TableType.HEAP);

    private static Position position(long scn) {
        return new Position(new SystemChangeNumber(scn),
                new LogPosition((byte) 1, 99L, 2, 3));
    }

    private static YStreamPosition expected(long scn) {
        return new YStreamPosition(scn, 1, 99L, 2, 3);
    }

    private static YstreamXactInterface xact(XactType type, Position position) {
        YstreamXactInterface lcr = mock(YstreamXactInterface.class);
        when(lcr.getLcrType()).thenReturn(LcrType.YSTREAM_XACT);
        when(lcr.getXactType()).thenReturn(type);
        when(lcr.getPosition()).thenReturn(position);
        return lcr;
    }

    /**
     * A real VARCHAR column carrying {@code value}; {@code null} when {@code isNull}.
     *
     * <p>Both library types are final, so Mockito 4 cannot mock them: the column metadata is
     * built through the library's own editor and the value goes through the library's own
     * {@code getData()} — with the JDBC {@code Accessor} faked, since decoding the wire
     * format is the driver's job, not Jync's.
     */
    private static YstreamColumn column(String name, String value, boolean isNull) {
        Column meta = Column.editor()
                .setColumnName(name)
                .setColumnId(1)
                .setColumnType(ColumnType.VARCHAR)
                .setDataType(Types.VARCHAR)
                .setTypeName("VARCHAR")
                .setPrecision(0)
                .setScale(0)
                .setNullable(true)
                .setSize(Math.max(1, isNull ? 1 : value.length()))
                .setCharLength(Math.max(1, isNull ? 1 : value.length()))
                .setLogicPosition(1)
                .create();
        YstreamColumn column = new YstreamColumn(1, isNull,
                isNull ? null : value.getBytes(StandardCharsets.UTF_8));
        column.setColumn(meta);
        if (!isNull) {
            Accessor accessor = mock(Accessor.class);
            try {
                when(accessor.getString(any())).thenReturn(value);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            column.setAccessor(accessor);
        }
        return column;
    }

    private static YstreamDmlInterface dml(DmlType type, YstreamColumns newValues,
                                           YstreamColumns oldValues, Position position) {
        YstreamDmlInterface lcr = mock(YstreamDmlInterface.class);
        when(lcr.getLcrType()).thenReturn(LcrType.YSTREAM_DML);
        when(lcr.getDmlType()).thenReturn(type);
        when(lcr.getTableId()).thenReturn(ORDERS);
        when(lcr.getNewValues()).thenReturn(newValues);
        when(lcr.getOldValues()).thenReturn(oldValues);
        when(lcr.getPosition()).thenReturn(position);
        return lcr;
    }

    @Test
    void transactionLcrsBecomeTheirOwnKinds() {
        assertThat(YstreamLcrAdapter.toEvent(xact(XactType.BEGIN, position(10L))).kind())
                .isEqualTo(Kind.XACT_BEGIN);
        assertThat(YstreamLcrAdapter.toEvent(xact(XactType.COMMIT, position(10L))).kind())
                .isEqualTo(Kind.XACT_COMMIT);
        assertThat(YstreamLcrAdapter.toEvent(xact(XactType.SAVEPOINT, position(10L))).kind())
                .isEqualTo(Kind.XACT_SAVEPOINT);
        assertThat(YstreamLcrAdapter
                .toEvent(xact(XactType.ROLLBACK_TO_SAVEPOINT, position(10L))).kind())
                .isEqualTo(Kind.XACT_ROLLBACK_TO_SAVEPOINT);
    }

    @Test
    void theCommitLcrCarriesThePositionJyncStores() {
        YStreamEvent event = YstreamLcrAdapter.toEvent(xact(XactType.COMMIT, position(42L)));

        assertThat(event.position()).isEqualTo(expected(42L));
    }

    @Test
    void anInsertCarriesItsNewImageAndItsTable() {
        YstreamColumns values = new YstreamColumns(List.of(
                column("ID", "7", false), column("NAME", "n", false), column("NOTE", null, true)));

        YStreamEvent event = YstreamLcrAdapter.toEvent(
                dml(DmlType.INSERT, values, YstreamColumns.EMPTY, position(11L)));

        assertThat(event.kind()).isEqualTo(Kind.DML);
        assertThat(event.operation()).isEqualTo(Operation.INSERT);
        assertThat(event.schema()).isEqualTo("APP");
        assertThat(event.table()).isEqualTo("ORDERS");
        assertThat(event.newValues()).containsEntry("ID", "7").containsEntry("NAME", "n");
        // NULL 列必须留在行里:少了它,UPSERT 的列清单就与源表不一致。
        assertThat(event.newValues()).containsKey("NOTE").containsEntry("NOTE", null);
    }

    @Test
    void anUpdateKeepsBothImagesWhenTheStreamSendsThem() {
        YstreamColumns newValues = new YstreamColumns(List.of(column("ID", "7", false)));
        YstreamColumns oldValues = new YstreamColumns(List.of(column("ID", "6", false)));

        YStreamEvent event = YstreamLcrAdapter.toEvent(
                dml(DmlType.UPDATE, newValues, oldValues, position(12L)));

        assertThat(event.operation()).isEqualTo(Operation.UPDATE);
        assertThat(event.newValues()).containsEntry("ID", "7");
        assertThat(event.oldValues()).containsEntry("ID", "6");
    }

    @Test
    void aDeleteIsBuiltFromTheOldImage() {
        // ALL 列补充日志下 old image 是全列镜像;DELETE 的定位键只能从这里来。
        YstreamColumns oldValues = new YstreamColumns(List.of(column("ID", "9", false)));

        YStreamEvent event = YstreamLcrAdapter.toEvent(
                dml(DmlType.DELETE, YstreamColumns.EMPTY, oldValues, position(13L)));

        assertThat(event.operation()).isEqualTo(Operation.DELETE);
        assertThat(event.oldValues()).containsEntry("ID", "9");
    }

    @Test
    void aChunkedDmlIsFlaggedRatherThanHalfDecoded() {
        YstreamColumns values = new YstreamColumns(List.of(column("ID", "7", false)));
        YstreamDmlInterface lcr = dml(DmlType.INSERT, values, YstreamColumns.EMPTY, position(14L));
        when(lcr.hasChunkData()).thenReturn(true);

        assertThat(YstreamLcrAdapter.toEvent(lcr).chunked()).isTrue();
    }

    @Test
    void aChunkLcrIsItsOwnKind() {
        YstreamDmlInterface lcr = dml(DmlType.CHUNK, YstreamColumns.EMPTY,
                YstreamColumns.EMPTY, position(15L));

        assertThat(YstreamLcrAdapter.toEvent(lcr).kind()).isEqualTo(Kind.CHUNK);
    }

    @Test
    void aDdlNamesTheTableItTouched() {
        YstreamDdlInterface lcr = mock(YstreamDdlInterface.class);
        when(lcr.getLcrType()).thenReturn(LcrType.YSTREAM_DDL);
        when(lcr.getTableId()).thenReturn(ORDERS);
        when(lcr.getPosition()).thenReturn(position(16L));

        YStreamEvent event = YstreamLcrAdapter.toEvent(lcr);

        assertThat(event.kind()).isEqualTo(Kind.DDL);
        assertThat(event.schema()).isEqualTo("APP");
        assertThat(event.table()).isEqualTo("ORDERS");
    }

    @Test
    void metadataAndSequenceLcrsAreIgnored() {
        for (LcrType type : List.of(LcrType.YSTREAM_METADATA, LcrType.YSTREAM_SEQ_HWM)) {
            YstreamLcrInterface lcr = mock(YstreamLcrInterface.class);
            when(lcr.getLcrType()).thenReturn(type);

            assertThat(YstreamLcrAdapter.toEvent(lcr).kind()).as("%s", type)
                    .isEqualTo(Kind.IGNORED);
        }
    }

    @Test
    void anLcrWithoutAPositionStillMapsCleanly() {
        YstreamXactInterface lcr = xact(XactType.BEGIN, null);

        assertThat(YstreamLcrAdapter.toEvent(lcr).position()).isNull();
    }

    /**
     * Measured on YashanDB 23.4.7.100: every stream opens with a metadata LCR, and a real
     * one throws from {@code getPosition()} instead of returning null. (The mock above
     * returns null by default, which is why this looked safe.)
     */
    @Test
    void aMetadataLcrThatRefusesItsPositionIsStillIgnored() {
        YstreamLcrInterface lcr = mock(YstreamLcrInterface.class);
        when(lcr.getLcrType()).thenReturn(LcrType.YSTREAM_METADATA);
        when(lcr.getPosition()).thenThrow(new YstreamUnsupportedOperationException(
                "Ystream-0102 UnsupportedOperation: YstreamMetadata does not have a position"));

        YStreamEvent event = YstreamLcrAdapter.toEvent(lcr);

        assertThat(event.kind()).isEqualTo(Kind.IGNORED);
        assertThat(event.position()).isNull();
    }

    /** The BEGIN of every real transaction refuses a position the same way. */
    @Test
    void aTransactionBeginThatRefusesItsPositionKeepsItsBoundary() {
        YstreamXactInterface lcr = xact(XactType.BEGIN, null);
        when(lcr.getPosition()).thenThrow(new YstreamUnsupportedOperationException(
                "Ystream-0102 UnsupportedOperation: YstreamXactBegin does not have a position"));

        YStreamEvent event = YstreamLcrAdapter.toEvent(lcr);

        assertThat(event.kind()).isEqualTo(Kind.XACT_BEGIN);
        assertThat(event.position()).isNull();
    }
}
