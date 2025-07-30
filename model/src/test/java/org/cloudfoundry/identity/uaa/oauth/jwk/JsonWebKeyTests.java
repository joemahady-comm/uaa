package org.cloudfoundry.identity.uaa.oauth.jwk;

import com.fasterxml.jackson.core.type.TypeReference;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.cloudfoundry.identity.uaa.test.ModelTestUtils.getResourceAsString;

class JsonWebKeyTests {

    private static final String samplKeys = getResourceAsString(JsonWebKeyDeserializerTest.class, "JwkSet-Microsoft.json");
    JsonWebKeySet<JsonWebKey> samlKeySet = JsonUtils.readValue(samplKeys, new TypeReference<JsonWebKeySet<JsonWebKey>>() {
    });

    @Test
    void webKeyPublic() {
        // given
        Map<String, Object> jsonMap = Map.of("kid", "uaa-key", "kty", "RSA");
        JsonWebKey jsonWebKey = new JsonWebKey(jsonMap);
        jsonWebKey.setKid(samlKeySet.getKeys().getFirst().getKid());
        jsonWebKey.setX5t(samlKeySet.getKeys().getFirst().getX5t());
        // then
        assertThat(jsonWebKey.getKid()).isEqualTo(samlKeySet.getKeys().getFirst().getKid());
        assertThat(jsonWebKey.getX5t()).isEqualTo(samlKeySet.getKeys().getFirst().getX5t());
        assertThat(((ArrayList) samlKeySet.getKeySetMap().get("keys"))).hasSize(3);
    }

    @Test
    void webKeyPublicNoTypeException() {
        // given
        Map<String, Object> jsonMap = Map.of("kid", "uaa-key");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new JsonWebKey(jsonMap));
    }

    @Test
    void rsaPublicKey() {
        // given
        String value = "-----BEGIN PUBLIC KEY-----\n" +
                "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvc5lyy8d+oOBDdgB8XsG\n" +
                "Nw4Zj7W6nbTF03qs/bL0CdgaIuMCK1hHDIBInHCRDYykIKwnwSdXi52QGeVAnUZa\n" +
                "86Tf/LyV+MNY7OOgTiQOP0fKwIt45MTGJX/FxKYMt3KZUkD9Xp+Pfj8/EeehvdY+\n" +
                "shOG6TwU1OayElu+WLQd7/qDsAjnNzhEv35R1BwMoMoSrN33d1HQSvFnNlX2R39q\n" +
                "X7YEc/bLG7JMBP/4MW3my2MK9AqC08w7P4dJkOVz+Zcf1wtV1oWX2Ddt4N4MdhGy\n" +
                "Fmg5S1XaZodaAL0T79VOOKVQ3fIxRaQjPZ522crpc6/AIFEPD+e4ezh5UcVaBI2N\n" +
                "uQIDAQAB\n" +
                "-----END PUBLIC KEY-----";
        Map<String, Object> jsonMap = Map.of(
                "kid", "uaa-key",
                "kty", "RSA",
                "e", "AQAB",
                "n", "vc5lyy8d-oOBDdgB8XsGNw4Zj7W6nbTF03qs_bL0CdgaIuMCK1hHDIBInHCRDYykIKwnwSdXi52QGeVAnUZa86Tf_LyV-MNY7OOgTiQOP0fKwIt45MTGJX_FxKYMt3KZUkD9Xp-Pfj8_EeehvdY-shOG6TwU1OayElu-WLQd7_qDsAjnNzhEv35R1BwMoMoSrN33d1HQSvFnNlX2R39qX7YEc_bLG7JMBP_4MW3my2MK9AqC08w7P4dJkOVz-Zcf1wtV1oWX2Ddt4N4MdhGyFmg5S1XaZodaAL0T79VOOKVQ3fIxRaQjPZ522crpc6_AIFEPD-e4ezh5UcVaBI2NuQ");
        JsonWebKey jsonWebKey = new JsonWebKey(jsonMap);
        assertThat(jsonWebKey.hasValue()).isFalse();
        assertThat(jsonWebKey.getValue()).isEqualTo(value);
    }

    @Test
    void rsaPublicKeyWithValue() {
        // given
        String value = "-----BEGIN PUBLIC KEY-----\n" +
                "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvc5lyy8d+oOBDdgB8XsG\n" +
                "Nw4Zj7W6nbTF03qs/bL0CdgaIuMCK1hHDIBInHCRDYykIKwnwSdXi52QGeVAnUZa\n" +
                "86Tf/LyV+MNY7OOgTiQOP0fKwIt45MTGJX/FxKYMt3KZUkD9Xp+Pfj8/EeehvdY+\n" +
                "shOG6TwU1OayElu+WLQd7/qDsAjnNzhEv35R1BwMoMoSrN33d1HQSvFnNlX2R39q\n" +
                "X7YEc/bLG7JMBP/4MW3my2MK9AqC08w7P4dJkOVz+Zcf1wtV1oWX2Ddt4N4MdhGy\n" +
                "Fmg5S1XaZodaAL0T79VOOKVQ3fIxRaQjPZ522crpc6/AIFEPD+e4ezh5UcVaBI2N\n" +
                "uQIDAQAB\n" +
                "-----END PUBLIC KEY-----";
        Map<String, Object> jsonMap = Map.of(
                "kid", "uaa-key",
                "kty", "RSA",
                "e", "AQAB",
                "value", value,
                "n", "vc5lyy8d-oOBDdgB8XsGNw4Zj7W6nbTF03qs_bL0CdgaIuMCK1hHDIBInHCRDYykIKwnwSdXi52QGeVAnUZa86Tf_LyV-MNY7OOgTiQOP0fKwIt45MTGJX_FxKYMt3KZUkD9Xp-Pfj8_EeehvdY-shOG6TwU1OayElu-WLQd7_qDsAjnNzhEv35R1BwMoMoSrN33d1HQSvFnNlX2R39qX7YEc_bLG7JMBP_4MW3my2MK9AqC08w7P4dJkOVz-Zcf1wtV1oWX2Ddt4N4MdhGyFmg5S1XaZodaAL0T79VOOKVQ3fIxRaQjPZ522crpc6_AIFEPD-e4ezh5UcVaBI2NuQ");
        JsonWebKey jsonWebKey = new JsonWebKey(jsonMap);
        assertThat(jsonWebKey.hasValue()).isTrue();
        assertThat(jsonWebKey.getValue()).isEqualTo(value);
    }
}
