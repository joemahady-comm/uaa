package org.cloudfoundry.identity.uaa.oauth.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ClientJwtCredentialTest {

    @Test
    void parse() {
        assertDoesNotThrow(() -> ClientJwtCredential.parse("[{\"iss\":\"http://localhost:8080/uaa\",\"sub\":\"client_with_jwks_trust\"}]"));
        List<ClientJwtCredential> federationList = ClientJwtCredential.parse("[{\"iss\":\"http://localhost:8080/uaa\",\"sub\":\"client_with_jwks_trust\"},{\"iss\":\"http://localhost:8080/uaa\",\"sub\":\"subject\"}]");
        assertEquals(2, federationList.size());
    }

    @Test
    void testConstructor() {
        ClientJwtCredential jwtCredential = new ClientJwtCredential("subject", "issuer", "audience");
        assertTrue(jwtCredential.getSubject().equals("subject"));
        assertTrue(jwtCredential.getIssuer().equals("issuer"));
        assertTrue(jwtCredential.getAudience().equals("audience"));
        assertNotNull(jwtCredential);
        try {
            jwtCredential = new ClientJwtCredential(null, null, null);
        } catch (IllegalArgumentException e) {
            // ignore
        }
    }

    @Test
    void testDeserializer() {
        assertTrue(ClientJwtCredential.parse("[{\"iss\":\"issuer\",\"sub\":\"subject\"}]").iterator().next().getSubject().equals("subject"));
    }

    @Test
    void testDeserializerException() {
        assertThrows(IllegalArgumentException.class, () -> ClientJwtCredential.parse("[\"iss\":\"issuer\"]"));
    }
}
