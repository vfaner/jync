package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.task.SyncLockService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Deleting a project holds its sync lease, deletes the lease row (the {@code sync_task} row
 * <em>is</em> the lease), and then closes the handle.
 *
 * <p>A close that still tries the database release would issue an UPDATE on the very row its
 * own transaction has deleted and not committed: a second connection blocks on the table lock
 * until H2's 2 s lock timeout and then fails, so every project deletion stalls for two seconds
 * and logs a misleading "Could not release the database sync lock" warning. The row is being
 * removed anyway — there is nothing to release.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:projectdelete;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class ProjectDeleteLockReleaseTest {

    private static final String RELEASE_WARNING = "Could not release the database sync lock";

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    private Logger lockLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLockLogs() {
        lockLogger = (Logger) LoggerFactory.getLogger(SyncLockService.class);
        appender = new ListAppender<>();
        appender.start();
        lockLogger.addAppender(appender);
    }

    @AfterEach
    void detachLockLogs() {
        lockLogger.detachAppender(appender);
    }

    @Test
    void deletingAProjectNeverTriesToReleaseTheLeaseRowItIsDeleting() {
        Project project = new Project();
        project.setName("delete-with-lease");
        Project saved = projectService.save(project);

        projectService.delete(saved.getId());

        assertThat(projectRepository.findById(saved.getId())).isEmpty();
        assertThat(releaseWarnings()).isEmpty();
    }

    private List<String> releaseWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(RELEASE_WARNING))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
