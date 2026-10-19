package com.vaultdrive.sharing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ShareLinkHttpFilterTest {
    @Test void requestBurstReturnsNoStore429BeforeDownstreamWorkAndDoesNotLogSecrets() throws Exception {
        var filter = new ShareLinkHttpFilter(new ShareLinkAdmission(1, 1, 1, 1, () -> 0));
        var request = new MockHttpServletRequest("GET", "/api/v1/public/share-links/" + UUID.randomUUID());
        request.addHeader(ShareLinkHttpFilter.TOKEN_HEADER, ShareLinkTokens.generate());
        var first = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, first, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        var next = new MockHttpServletResponse(); var rejectedChain = new MockFilterChain();
        filter.doFilter(request, next, rejectedChain);
        assertThat(next.getStatus()).isEqualTo(429);
        assertThat(next.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(next.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejectedChain.getRequest()).isNull();
        assertThat(next.getContentAsString()).doesNotContain(request.getHeader(ShareLinkHttpFilter.TOKEN_HEADER));
    }
}
