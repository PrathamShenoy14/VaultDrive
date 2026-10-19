package com.vaultdrive.sharing;

import java.util.List;

public enum ResourceRole {
    VIEWER, EDITOR, OWNER;

    // Role privileges; TRASH additionally requires an editable immediate parent.
    public List<String> capabilities(boolean folder) {
        if (this == OWNER) {
            return folder
                    ? List.of("READ_METADATA", "LIST_CHILDREN", "CREATE_FOLDER", "UPLOAD", "RENAME",
                              "MOVE", "TRASH", "RESTORE", "PURGE", "MANAGE_GRANTS")
                    : List.of("READ_METADATA", "DOWNLOAD", "RENAME", "MOVE", "TRASH", "RESTORE",
                              "PURGE", "MANAGE_GRANTS");
        }
        if (this == EDITOR) {
            return folder ? List.of("READ_METADATA", "LIST_CHILDREN", "CREATE_FOLDER", "UPLOAD", "RENAME", "TRASH")
                    : List.of("READ_METADATA", "DOWNLOAD", "RENAME", "TRASH");
        }
        return folder ? List.of("READ_METADATA", "LIST_CHILDREN") : List.of("READ_METADATA", "DOWNLOAD");
    }
}
