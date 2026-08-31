package com.synctool.dto;

import com.synctool.model.ChangeType;
import com.synctool.model.ObjectType;

import lombok.Getter;
import lombok.Setter;

/** A structural difference between the source and the last snapshot. */
@Getter
@Setter
public class ChangeEvent {

    private ObjectType objectType;

    private ChangeType changeType;

    private String objectName;

    /** Sub-object name, e.g. the column or index affected by an ALTER. */
    private String detailName;

    /** Human-readable summary shown in the change log. */
    private String description;

    /** Payload the sync engine needs to apply the change (TableMeta, ViewMeta, ...). */
    private Object payload;

    /**
     * Apply order. Lower runs first: tables before indexes, indexes before views,
     * views before procedures, so dependencies exist by the time they are referenced.
     */
    private int priority;

    public static ChangeEvent of(ObjectType objectType, ChangeType changeType,
                                 String objectName, String description, Object payload) {
        ChangeEvent e = new ChangeEvent();
        e.objectType = objectType;
        e.changeType = changeType;
        e.objectName = objectName;
        e.description = description;
        e.payload = payload;
        e.priority = defaultPriority(objectType);
        return e;
    }

    private static int defaultPriority(ObjectType type) {
        switch (type) {
            case TABLE:
                return 10;
            case COLUMN:
                return 20;
            case INDEX:
                return 30;
            case VIEW:
                return 40;
            case PROCEDURE:
            case FUNCTION:
                return 50;
            case DATA:
                return 60;
            default:
                return 100;
        }
    }

    @Override
    public String toString() {
        return changeType + " " + objectType + " " + objectName
                + (detailName != null ? "." + detailName : "");
    }
}
