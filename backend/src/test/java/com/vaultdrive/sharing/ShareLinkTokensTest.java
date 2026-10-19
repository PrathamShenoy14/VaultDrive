package com.vaultdrive.sharing;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ShareLinkTokensTest {
    @Test void generatesIndependent256BitSecretsAndHashesExactlyTheReturnedSecret() throws Exception {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = ShareLinkTokens.generate();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            assertThat(tokens.add(token)).isTrue();
            assertThat(ShareLinkTokens.hash(token)).isEqualTo(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Test void rejectsMissingMalformedOversizedAndNonCanonicalSecrets() {
        for (String token : new String[]{null, "", "bad", "A".repeat(44), "A".repeat(42) + "B", " ".repeat(43)}) {
            assertThatThrownBy(() -> ShareLinkTokens.hash(token)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
}
