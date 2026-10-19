package com.vaultdrive.sharing;

import java.util.List;

public enum ResourceRole {
    VIEWER, EDITOR, OWNER;

    // M1 deliberately gives Editor the same read capabilities as Viewer.
    public List<String> capabilities(boolean folder) {
        if (this == OWNER) {
            return folder
                    ? List.of("READ_METADATA", "LIST_CHILDREN", "CREATE_FOLDER", "UPLOAD", "RENAME",
                              "MOVE", "TRASH", "RESTORE", "PURGE", "MANAGE_GRANTS")
                    : List.of("READ_METADATA", "DOWNLOAD", "RENAME", "MOVE", "TRASH", "RESTORE",
                              "PURGE", "MANAGE_GRANTS");
        }
        return folder ? List.of("READ_METADATA", "LIST_CHILDREN") : List.of("READ_METADATA", "DOWNLOAD");
    }
}
