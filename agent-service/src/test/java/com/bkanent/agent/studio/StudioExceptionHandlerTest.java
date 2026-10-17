package com.bkanent.agent.studio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bkanent.common.model.ApiResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** The generic 503 handler must log the trigger: without it the cause is invisible in deployed jars (KI-126). */
class StudioExceptionHandlerTest {

    @Test
    void genericUnavailableHandlerLogsTheTriggeringException() {
        Logger logger = (Logger) LoggerFactory.getLogger(StudioExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ResponseEntity<ApiResponse<Void>> response = new StudioExceptionHandler()
                    .unavailable(new IllegalStateException("boom-diagnostic"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().code()).isEqualTo("STUDIO_DEPENDENCY_UNAVAILABLE");
            assertThat(appender.list).isNotEmpty();
            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("studio request degraded");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        } finally {
            logger.detachAppender(appender);
        }
    }
}
