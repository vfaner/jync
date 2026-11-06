package com.qqmu.jync.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * A candidate metadata-database connection typed into the 系统配置 page.
 *
 * <p>Never persisted: the form tests a connection and prints the environment block the operator
 * would paste. Keeping no row means there is no second pointer that could disagree with
 * {@code SPRING_DATASOURCE_*}, and no stored credential for a database the application does not
 * actually use.
 */
@Getter
@Setter
public class MetadataDbTestForm {

    private String url;
    private String driverClass;
    private String username;
    private String password;
}
