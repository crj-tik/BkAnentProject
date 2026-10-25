package com.bkanent.common.authz;

import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the existing constructor-based Dubbo serializer, without changing its SPI. */
class AuthzSerializationTest {
    @Test
    void signedResponseAndBindingsRoundTripExistingNativeRpcSerialization() throws Exception {
        var key = keyPair();
        AuthorizationSnapshot snapshot = AuthzSignature.sign(snapshot(), key.getPrivate());
        AuthorizationContext context = AuthzSignature.sign(context(snapshot), key.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, AuthzSignature.sign(freshness(snapshot), key.getPrivate()));
        var serialization = new Fastjson2ConstructorSerialization();
        for (Object dto : List.of(response, binding(), request(List.of(new RequestBinding.QueryParameter("a", "1"))),
                AuthzSignature.sign(caller(binding(), context), key.getPrivate()))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ObjectOutput output = serialization.serialize(null, bytes);
            output.writeObject(dto);
            output.flushBuffer();
            ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(dto, input.readObject(dto.getClass()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeObject(response);
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        AuthzResponse restored = input.readObject(AuthzResponse.class);
        AuthzSignature.verifyResponse(restored, "agent-service", context.requestId(), HASH, NONCE,
                Map.of(KID, key.getPublic()), java.time.Instant.ofEpochSecond(NOW));
        assertThrows(UnsupportedOperationException.class, () -> restored.context().allowedOperations().clear());
    }
}
