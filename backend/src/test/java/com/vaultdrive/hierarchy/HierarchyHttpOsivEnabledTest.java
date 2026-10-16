package com.vaultdrive.hierarchy;

import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "spring.jpa.open-in-view=true")
class HierarchyHttpOsivEnabledTest extends HierarchyHttpTestSupport {
    /** Diagnostic control: explicitly opting back into OSIV reproduces the framework wrapper mismatch. */
    @ParameterizedTest
    @ValueSource(strings = {"folderRestore", "folderPurge", "folderCreate", "fileRestore", "fileRename"})
    void osivWrapperReportsUnjoinedDespiteAnActiveNativeTransaction(String operation) throws Exception {
        var before = snapshot();
        switch (operation) {
            case "folderRestore" -> request("POST", restore(trashed), null, 500);
            case "folderPurge" -> request("DELETE", purge(trashed), null, 500);
            case "folderCreate" -> request("POST", "/folders", "{\"name\":\"Created\"}", 500);
            case "fileRestore" -> request("POST", "/files/" + trashedFile + "/restore", null, 500);
            case "fileRename" -> request("PATCH", "/files/" + activeFile + "/name", "{\"name\":\"Renamed\"}", 500);
            default -> throw new IllegalArgumentException(operation);
        }
        assertThat(states).containsExactly(new TxState(true, false, true, true, true, true));
        assertThat(snapshot()).isEqualTo(before);
    }
}
