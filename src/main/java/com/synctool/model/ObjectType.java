package com.synctool.model;

/** Kinds of database object the tool can synchronize. */
public enum ObjectType {
    TABLE,
    COLUMN,
    INDEX,
    VIEW,
    PROCEDURE,
    FUNCTION,
    DATA,
    PROJECT
}
