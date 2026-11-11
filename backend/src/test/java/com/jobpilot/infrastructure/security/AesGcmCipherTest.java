package com.jobpilot.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

    private final AesGcmCipher cipher = new AesGcmCipher("unit-test-secret");

    @Test
    void roundTripRecoversPlaintext() {
        String cipherText = cipher.encrypt("sk-1234567890abcdef");

        assertThat(cipherText).doesNotContain("sk-");
        assertThat(cipher.decrypt(cipherText)).isEqualTo("sk-1234567890abcdef");
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void tamperedCiphertextFails() {
        String encrypted = cipher.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherKeyCannotDecrypt() {
        String encrypted = cipher.encrypt("secret");

        assertThatThrownBy(() -> new AesGcmCipher("another-secret").decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> new AesGcmCipher(" ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maskKeepsPrefixAndSuffixOnly() {
        assertThat(AesGcmCipher.mask("sk-1234567890MwMY")).isEqualTo("sk-****MwMY");
        assertThat(AesGcmCipher.mask("short")).isEqualTo("****");
        assertThat(AesGcmCipher.mask(null)).isEmpty();
    }
}
