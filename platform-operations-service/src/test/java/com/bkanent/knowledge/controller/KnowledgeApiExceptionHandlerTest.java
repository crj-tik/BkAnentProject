package com.bkanent.knowledge.controller;

import com.bkanent.knowledge.domain.KnowledgeOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeApiExceptionHandlerTest {
    private final KnowledgeApiExceptionHandler handler = new KnowledgeApiExceptionHandler();

    @Test
    void malformedJsonUsesTheSameClientErrorEnvelopeStatus() {
        var failure = new HttpMessageNotReadableException("malformed", new MockHttpInputMessage(new byte[0]));

        assertThat(handler.unreadable(failure).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void operationErrorsPreserveTheirDeclaredStatus() {
        assertThat(handler.operation(new KnowledgeOperationException("VERSION_CONFLICT", 409))
                .getStatusCode().value()).isEqualTo(409);
    }
}
