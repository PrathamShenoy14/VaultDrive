package com.vaultdrive.folder;

import com.vaultdrive.folder.exception.FolderNotFoundException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class FolderAccessValidator {

    private final FolderRepository folderRepository;

    public FolderAccessValidator(
            FolderRepository folderRepository
    ) {
        this.folderRepository = folderRepository;
    }

    public Folder requireAccessibleFolder(
            UUID ownerId,
            UUID folderId
    ) {
        Folder folder = folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderId,
                        ownerId
                )
                .orElseThrow(() ->
                        new FolderNotFoundException(
                                "Folder not found"
                        )
                );

        validateAncestorChain(
                ownerId,
                folder.getParentFolderId()
        );

        return folder;
    }

    public Folder requireAccessibleParent(
            UUID ownerId,
            UUID parentFolderId
    ) {
        if (parentFolderId == null) {
            return null;
        }

        Folder parent = folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        parentFolderId,
                        ownerId
                )
                .orElseThrow(() ->
                        new FolderNotFoundException(
                                "Parent folder not found"
                        )
                );

        validateAncestorChain(
                ownerId,
                parent.getParentFolderId()
        );

        return parent;
    }

    private void validateAncestorChain(
            UUID ownerId,
            UUID parentFolderId
    ) {
        Set<UUID> visited = new HashSet<>();

        UUID currentId = parentFolderId;

        while (currentId != null) {

            if (!visited.add(currentId)) {
                throw new IllegalStateException(
                        "Folder hierarchy contains a cycle"
                );
            }

            Folder currentFolder = folderRepository
                    .findByIdAndOwnerIdAndDeletedAtIsNull(
                            currentId,
                            ownerId
                    )
                    .orElseThrow(() ->
                            new FolderNotFoundException(
                                    "Parent folder not found"
                            )
                    );

            currentId =
                    currentFolder.getParentFolderId();
        }
    }
}
