package com.bkanent.common.serialize;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AuthzExceptionSerializationTest {
    @Test
    void everyAuthorizationErrorRoundTripsWithoutArbitraryExceptionContent() throws Exception {
        var serialization = new Fastjson2ConstructorSerialization();
        for (AuthzErrorCode code : AuthzErrorCode.values()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            var output = serialization.serialize(null, bytes);
            output.writeThrowable(new AuthzException(code));
            output.flushBuffer();
            var input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
            var restored = assertInstanceOf(AuthzException.class, input.readThrowable());
            assertEquals(code, restored.code());
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("stackTrace"));
        }
    }

    @Test
    void unknownAuthorizationErrorCannotCreateAnArbitraryThrowable() {
        var serialization = new Fastjson2ConstructorSerialization();
        byte[] bytes = "{\"@throwable\":\"AuthzException\",\"code\":\"FOREIGN\",\"message\":\"private\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> serialization.deserialize(null,
                new ByteArrayInputStream(bytes)).readThrowable());
    }
}
