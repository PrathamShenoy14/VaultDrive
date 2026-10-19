package com.vaultdrive.folder;

import com.vaultdrive.folder.dto.FolderResponse;
import com.vaultdrive.folder.dto.TrashFolderResponse;
import com.vaultdrive.folder.dto.RestoreFolderResponse;

import com.vaultdrive.folder.exception.DuplicateFolderNameException;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.folder.exception.InvalidFolderMoveException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.sharing.ResourceReadService;
import com.vaultdrive.sharing.ResourceAccess;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxWriter;
import com.vaultdrive.worker.FolderPurgeRunService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultdrive.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final FolderAccessValidator folderAccessValidator;
    private final FolderNameValidator folderNameValidator;
    private final UserRepository userRepository;
    private final HierarchyCoordinator hierarchyCoordinator;
    private final OutboxWriter outboxWriter;
    private final FolderPurgeRunService purgeRuns;
    private final ResourceReadService resourceReads;

    public FolderService(
            FolderRepository folderRepository,
            FolderAccessValidator folderAccessValidator,
            FolderNameValidator folderNameValidator,
            UserRepository userRepository,
            HierarchyCoordinator hierarchyCoordinator,
            OutboxWriter outboxWriter,
            FolderPurgeRunService purgeRuns,
            ResourceReadService resourceReads
    ) {
        this.folderRepository = folderRepository;
        this.folderAccessValidator = folderAccessValidator;
        this.folderNameValidator = folderNameValidator;
        this.userRepository = userRepository;
        this.hierarchyCoordinator = hierarchyCoordinator;
        this.outboxWriter = outboxWriter;
        this.purgeRuns = purgeRuns;
        this.resourceReads = resourceReads;
    }

    @Transactional
    public UUID createFolder(
            UUID actorId,
            UUID parentFolderId,
            String name
    ) {

        UUID ownerId = parentFolderId == null ? actorId : resourceReads.owner(true, parentFolderId);
        lockFolderNamespace(ownerId);
        if (parentFolderId != null) resourceReads.requireEditor(actorId, true, parentFolderId, false);
        
        String normalizedName =
                folderNameValidator.validateAndNormalize(name);

        folderAccessValidator.requireAccessibleParent(ownerId, parentFolderId);

        boolean nameExists;

        if (parentFolderId == null) {

            nameExists = folderRepository
                    .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                            ownerId,
                            normalizedName
                    );

        } else {

            nameExists = folderRepository
                    .existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
                            ownerId,
                            parentFolderId,
                            normalizedName
                    );
        }

        if (nameExists) {
            throw new DuplicateFolderNameException(
                    "A folder with this name already exists"
            );
        }

        Folder folder = new Folder(
                ownerId,
                parentFolderId,
                normalizedName
        );

        folderRepository.saveAndFlush(folder);

        return folder.getId();
    }

    public FolderResponse getFolder(UUID actorId, UUID folderId) {
        return resourceReads.folder(actorId, folderId);
    }

    public List<FolderResponse> listRootFolders(UUID actorId) {
        return resourceReads.folders(actorId, null);
    }

    public List<FolderResponse> listChildFolders(UUID actorId, UUID parentFolderId) {
        return resourceReads.folders(actorId, parentFolderId);
    }

    @Transactional
    public FolderResponse renameFolder(
            UUID actorId,
            UUID folderId,
            String newName
    ) {

        UUID ownerId = resourceReads.owner(true, folderId);
        lockFolderNamespace(ownerId);
        ResourceAccess permission = resourceReads.requireEditor(actorId, true, folderId, false);
        
        // Validate and normalize the requested name.
        String normalizedName =
                folderNameValidator.validateAndNormalize(newName);

        // Verify ownership and ensure the folder is active.
        Folder folder =
                folderAccessValidator.requireAccessibleFolder(
                        ownerId,
                        folderId
                );

        resourceReads.refreshForMutation(folder);

        // Renaming to the existing name is a successful no-op.
        if (folder.getName().equals(normalizedName)) {
            return toResponse(folder, permission);
        }

        // Check for duplicates within the same parent.
        boolean nameExists;

        if (folder.getParentFolderId() == null) {
            nameExists = folderRepository
                    .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                            ownerId,
                            normalizedName
                    );
        } else {
            nameExists = folderRepository
                    .existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
                            ownerId,
                            folder.getParentFolderId(),
                            normalizedName
                    );
        }

        if (nameExists) {
            throw new DuplicateFolderNameException(
                    "A folder with this name already exists"
            );
        }

        // JPA tracks changes to this entity within the transaction.
        folder.rename(normalizedName);

        // Flush now so database uniqueness violations surface here.
        folderRepository.flush();

        return toResponse(folder, permission);
    }

    @Transactional
    public FolderResponse moveFolder(
            UUID ownerId,
            UUID folderId,
            UUID destinationFolderId
    ) {

        resourceReads.requireOwner(ownerId, true, folderId);
        lockFolderNamespace(ownerId);
        
        // 1. Verify ownership and ensure the source folder and its ancestors are active.
        Folder folder =
                folderAccessValidator.requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        // 2. Moving a folder into itself is invalid.
        if (folderId.equals(destinationFolderId)) {
            throw new InvalidFolderMoveException(
                    "A folder cannot be moved into itself"
            );
        }
    
        // 3. Moving to the current location is a successful no-op.
        if (java.util.Objects.equals(
                folder.getParentFolderId(),
                destinationFolderId
        )) {
            return toResponse(folder);
        }
    
        // 4. Validate the destination and prevent circular hierarchies.
        Set<UUID> visited = new HashSet<>();
    
        UUID currentId = destinationFolderId;
    
        while (currentId != null) {
    
            if (currentId.equals(folderId)) {
                throw new InvalidFolderMoveException(
                        "A folder cannot be moved into its own descendant"
                );
            }
    
            if (!visited.add(currentId)) {
                throw new IllegalStateException(
                        "Cycle detected in folder hierarchy"
                );
            }
    
            Folder currentFolder = folderRepository
                    .findByIdAndOwnerIdAndDeletedAtIsNull(
                            currentId,
                            ownerId
                    )
                    .orElseThrow(() ->
                            new FolderNotFoundException(
                                    "Destination folder not found"
                            )
                    );
    
            currentId = currentFolder.getParentFolderId();
        }
    
        // 5. Check for duplicate names at the destination.
        boolean nameExists;
    
        if (destinationFolderId == null) {
            nameExists = folderRepository
                    .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                            ownerId,
                            folder.getName()
                    );
        } else {
            nameExists = folderRepository
                    .existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
                            ownerId,
                            destinationFolderId,
                            folder.getName()
                    );
        }
    
        if (nameExists) {
            throw new DuplicateFolderNameException(
                    "A folder with this name already exists in the destination"
            );
        }
    
        // 6. Update the folder's parent and modification timestamp.
        folder.move(destinationFolderId);
    
        // Flush to detect database constraint violations immediately.
        folderRepository.flush();
    
        return toResponse(folder);
    }

    @Transactional
    public void deleteFolder(UUID actorId, UUID folderId) {
        UUID ownerId = resourceReads.owner(true, folderId);
        lockFolderNamespace(ownerId);
        resourceReads.requireEditor(actorId, true, folderId, true);
    
        Folder folder =
                folderAccessValidator.requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        resourceReads.refreshForMutation(folder);
        folder.softDelete();
        folderRepository.flush();
    }

    @Transactional(readOnly = true)
    public List<TrashFolderResponse> listTrashedFolders(UUID ownerId) {
    
        return folderRepository
                .findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        ownerId
                )
                .stream()
                .map(folder -> new TrashFolderResponse(
                        folder.getId(),
                        folder.getName(),
                        folder.getParentFolderId(),
                        folder.getDeletedAt()
                ))
                .sorted(
                        java.util.Comparator
                                .comparing(
                                        TrashFolderResponse::deletedAt
                                )
                                .reversed()
                                .thenComparing(TrashFolderResponse::id)
                )
                .toList();
    }

    @Transactional
    public RestoreFolderResponse restoreFolder(
            UUID ownerId,
            UUID folderId
    ) {

        resourceReads.requireOwner(ownerId, true, folderId);
        lockFolderNamespace(ownerId);
        
        // Only explicitly deleted folders without pending purge can be restored.
        Folder folder = folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNotNull(
                        folderId,
                        ownerId
                )
                .filter(candidate ->
                        candidate.getPurgeRequestedAt() == null
                )
                .orElseThrow(() ->
                        new FolderNotFoundException("Deleted folder not found")
                );
    
        UUID destinationParentId = folder.getParentFolderId();

        folderAccessValidator.requireNoPendingPurgeInAncestry(
                ownerId,
                destinationParentId
        );
    
        // Determine whether the original parent is still accessible.
        if (destinationParentId != null) {
            try {
                folderAccessValidator.requireAccessibleFolder(
                                ownerId,
                                destinationParentId
                        );
            } catch (FolderNotFoundException exception) {
                // The original parent is deleted or inaccessible.
                // Restore the folder to the root instead.
                destinationParentId = null;
            }
        }
    
        // Resolve name conflicts at the restoration destination.
        String restoredName = generateRestoredName(
                ownerId,
                destinationParentId,
                folder.getName()
        );
    
        // Preserve the original folder ID.
        folder.restore(destinationParentId, restoredName);
    
        folderRepository.flush();
    
        return new RestoreFolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId()
        );
    }

    @Transactional(timeout = 10)
    public void requestPermanentDeletion(
            UUID ownerId,
            UUID folderId
    ) {
        purgeRuns.boundTransaction();
        resourceReads.requireOwner(ownerId, true, folderId);
        lockFolderNamespace(ownerId);

        Folder folder = folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNotNull(
                        folderId,
                        ownerId
                )
                .filter(candidate ->
                        candidate.getPurgeRequestedAt() == null
                )
                .orElseThrow(() ->
                        new FolderNotFoundException(
                                "Deleted folder not found"
                        )
                );

        folderAccessValidator.requireNoPendingPurgeInAncestry(
                ownerId,
                folder.getParentFolderId()
        );

        if (folderRepository.hasPendingPurgeInSubtree(
                ownerId,
                folderId
        )) {
            throw new FolderNotFoundException(
                    "Deleted folder not found"
            );
        }

        folder.requestPermanentDeletion();
        folderRepository.flush();
        OutboxEvent event = OutboxEvent.folderPurgeRequested(
                ownerId, folderId, folder.getPurgeRequestedAt()
        );
        outboxWriter.append(event);
        purgeRuns.create(event);
    }

    private void lockFolderNamespace(UUID ownerId) {
        hierarchyCoordinator.acquireExclusive(ownerId);

        if (!userRepository.existsById(ownerId)) {
            throw new AccessDeniedException("User not found");
        }
    }
    
    private String generateRestoredName(
            UUID ownerId,
            UUID parentFolderId,
            String originalName
    ) {
        if (!activeFolderNameExists(
                ownerId,
                parentFolderId,
                originalName
        )) {
            return originalName;
        }
    
        int suffix = 1;
    
        while (true) {
            String suffixText = suffix == 1
                    ? " (restored)"
                    : " (restored " + suffix + ")";
    
            // PostgreSQL limits folder names to 255 characters.
            int maximumBaseLength = 255 - suffixText.length();
    
            String baseName = originalName.substring(
                    0,
                    Math.min(originalName.length(), maximumBaseLength)
            );
    
            String candidate = baseName + suffixText;
    
            if (!activeFolderNameExists(
                    ownerId,
                    parentFolderId,
                    candidate
            )) {
                return candidate;
            }
    
            suffix++;
        }
    }
    
    private boolean activeFolderNameExists(
            UUID ownerId,
            UUID parentFolderId,
            String name
    ) {
        if (parentFolderId == null) {
            return folderRepository
                    .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                            ownerId,
                            name
                    );
        }
    
        return folderRepository
                .existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
                        ownerId,
                        parentFolderId,
                        name
                );
    }

    private FolderResponse toResponse(Folder folder, ResourceAccess permission) {
        return new FolderResponse(folder.getId(), folder.getName(),
                permission.parentVisible() ? folder.getParentFolderId() : null,
                folder.getCreatedAt(), folder.getUpdatedAt(), permission.role(), permission.role().capabilities(true));
    }

    // Convert our JPA entity into a public API response.

    private FolderResponse toResponse(Folder folder) {

        return new FolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getParentFolderId(),
                folder.getCreatedAt(),
                folder.getUpdatedAt()
        );
    }
}
