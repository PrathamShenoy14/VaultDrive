package com.vaultdrive.hierarchy;

import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class HierarchyHttpOsivDisabledTest extends HierarchyHttpTestSupport {
    @Test void explicitOsivDisabledUsesTheJoinedNativeEntityManager() throws Exception {
        request("POST", restore(trashed), null, 200);
        assertJoinedRequests();
        assertThat(folders.findById(trashed).orElseThrow().getDeletedAt()).isNull();
    }
}
