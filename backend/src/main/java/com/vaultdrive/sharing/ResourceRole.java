package com.vaultdrive.sharing;

import java.util.List;

public enum ResourceRole {
    VIEWER, EDITOR, OWNER;

    // Active-resource baseline. Snapshots further restrict immediate-parent eligibility.
    public List<String> capabilities(boolean folder) {
        if (this == OWNER) {
            return folder
                    ? List.of("READ_METADATA", "LIST_CHILDREN", "CREATE_FOLDER", "UPLOAD", "RENAME",
                              "MOVE", "TRASH", "MANAGE_GRANTS")
                    : List.of("READ_METADATA", "DOWNLOAD", "COPY", "RENAME", "MOVE", "TRASH", "MANAGE_GRANTS");
        }
        if (this == EDITOR) {
            return folder ? List.of("READ_METADATA", "LIST_CHILDREN", "CREATE_FOLDER", "UPLOAD", "RENAME", "TRASH")
                    : List.of("READ_METADATA", "DOWNLOAD", "RENAME", "TRASH");
        }
        return folder ? List.of("READ_METADATA", "LIST_CHILDREN") : List.of("READ_METADATA", "DOWNLOAD");
    }
}
