package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * Hibernate 7 maps {@code @Enumerated(STRING)} to the database's native ENUM where the dialect
 * has one, and pins that ENUM to the constants of the release that created the column.
 * {@code ddl-auto=update} compares only the type name, so it never widens an existing enum when
 * a later release adds a constant: the first read or write of the new constant fails (H2 error
 * 22030). That is how 2.4.1 broke upgrades — the local OPS account seeding queried
 * {@code app_user.role = 'OPS'} on a store whose role column still knew only ADMIN and VIEWER,
 * the exception escaped the {@code ApplicationReadyEvent} listener, and the process exited.
 *
 * <p>Every enum column is therefore mapped with {@code @JdbcTypeCode(SqlTypes.VARCHAR)}, which
 * makes Hibernate expect text: a store created by an earlier release is converted by Hibernate's
 * own schema update before any bean runs, and a fresh install is born as text rather than as a
 * native ENUM. Hibernate still writes the constant set into a CHECK constraint, which
 * {@code MetadataEnumColumnMigration} drops on H2, MySQL, PostgreSQL and Oracle. This test fails
 * when a new enum field forgets the annotation, so the regression is caught here instead of on a
 * user's upgrade.
 */
class EnumColumnsStoredAsTextTest {

    @Test
    void everyEnumFieldIsMappedToText() throws ClassNotFoundException {
        List<String> offenders = new ArrayList<>();
        int enumFields = 0;
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.qqmu.jync.model")) {
            Class<?> entity = Class.forName(definition.getBeanClassName());
            for (Field field : entity.getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    continue;
                }
                enumFields++;
                JdbcTypeCode code = field.getAnnotation(JdbcTypeCode.class);
                if (code == null || code.value() != SqlTypes.VARCHAR) {
                    offenders.add(entity.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(enumFields).isPositive();
        assertThat(offenders).as("enum fields without @JdbcTypeCode(SqlTypes.VARCHAR)")
                .isEmpty();
    }
}
