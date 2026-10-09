package com.qqmu.jync.config;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Wires Quartz jobs into the Spring context.
 *
 * <p>Quartz instantiates job classes reflectively, so without this factory a job's
 * {@code @Autowired} collaborators would be null. The default
 * {@link SpringBeanJobFactory} handles job data map binding but not dependency injection.
 */
@Configuration
public class QuartzConfig {

    @Bean
    public SpringBeanJobFactory jobFactory(ApplicationContext applicationContext) {
        AutowiringSpringBeanJobFactory factory = new AutowiringSpringBeanJobFactory();
        factory.setApplicationContext(applicationContext);
        return factory;
    }

    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(SpringBeanJobFactory jobFactory) {
        SchedulerFactoryBean scheduler = new SchedulerFactoryBean();
        scheduler.setJobFactory(jobFactory);
        // Shutdown does not block here: an unbounded wait let a wedged cycle hang process
        // exit forever. BoundedQuartzShutdown (a context-closed listener) gives in-flight
        // cycles a configured grace period before this non-blocking shutdown runs.
        scheduler.setWaitForJobsToCompleteOnShutdown(false);
        scheduler.setOverwriteExistingJobs(true);
        scheduler.setAutoStartup(true);
        return scheduler;
    }

    @Bean
    public BoundedQuartzShutdown boundedQuartzShutdown(org.quartz.Scheduler scheduler,
                                                       SyncProperties properties) {
        return new BoundedQuartzShutdown(scheduler, properties);
    }

    /** Applies Spring autowiring to every Quartz-created job instance. */
    static class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory {

        private AutowireCapableBeanFactory beanFactory;

        @Override
        public void setApplicationContext(ApplicationContext context) {
            super.setApplicationContext(context);
            this.beanFactory = context.getAutowireCapableBeanFactory();
        }

        @Override
        protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
            Object job = super.createJobInstance(bundle);
            beanFactory.autowireBean(job);
            return job;
        }
    }
}
