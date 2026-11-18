package com.qqmu.jync.model;

/**
 * The three fixed roles.
 *
 * <p>Write access is deliberately two questions, not one bit: whether the role may write at
 * all ({@link #ADMIN} and {@link #OPS}), and whether this particular resource belongs to the
 * caller (the owner column on the resource). A viewer answers no to the first; an operator
 * answers yes to the first and "only mine" to the second.
 */
public enum UserRole {

    /** 只读：可查看全部业务资源，只能修改自己的密码。 */
    VIEWER,

    /** 运维：可查看全部资源，只能编辑/删除/运行自己名下的资源。 */
    OPS,

    /** 系统管理员：全部业务权限 + 全部系统面权限。 */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
