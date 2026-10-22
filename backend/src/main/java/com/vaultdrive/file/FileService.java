package com.vaultdrive.file;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.activity.ResourceActivityService.Snapshot;
import static com.vaultdrive.activity.ResourceActivityService.EventType.*;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.dto.FileDownload;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.sharing.ResourceReadService;
import com.vaultdrive.sharing.ResourceAccess;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageObject;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FileService {

    private final FolderAccessValidator folderAccessValidator;
    private final FileNameValidator fileNameValidator;
    private final FileMetadataService fileMetadataService;
    private final ObjectStorageService objectStorageService;
    private final StoredFileRepository storedFileRepository;
    private final FileNameExtensionResolver fileNameExtensionResolver;
    private final HierarchyCoordinator hierarchyCoordinator;
    private final ResourceReadService resourceReads;
    private final ResourceActivityService activity;

    public FileService(
            FolderAccessValidator folderAccessValidator,
            FileNameValidator fileNameValidator,
            FileMetadataService fileMetadataService,
            ObjectStorageService objectStorageService,
            StoredFileRepository storedFileRepository,
            FileNameExtensionResolver fileNameExtensionResolver,
            HierarchyCoordinator hierarchyCoordinator,
            ResourceReadService resourceReads,
            ResourceActivityService activity
    ) {
        this.folderAccessValidator = folderAccessValidator;
        this.fileNameValidator = fileNameValidator;
        this.fileMetadataService = fileMetadataService;
        this.objectStorageService = objectStorageService;
        this.storedFileRepository = storedFileRepository;
        this.fileNameExtensionResolver = fileNameExtensionResolver;
        this.hierarchyCoordinator = hierarchyCoordinator;
        this.resourceReads = resourceReads;
        this.activity = activity;
    }

    public FilePageResponse listFiles(UUID actorId, UUID folderId, int page, int size) {
        return resourceReads.files(actorId, folderId, page, size);
    }

    public FileResponse getFile(UUID actorId, UUID fileId) {
        return resourceReads.file(actorId, fileId).fileResponse();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public FileDownload downloadFile(UUID actorId, UUID fileId) {
        ResourceAccess file = resourceReads.file(actorId, fileId);
        // Authorization transaction has committed. An admitted stream may finish after revocation.
        StorageObject storageObject;
        try {
            storageObject = objectStorageService.download(file.storageKey());
        } catch (RuntimeException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "Download unavailable");
        }
        return new FileDownload(file.name(), file.contentType(), file.sizeBytes(), storageObject.inputStream());
    }

    @Transactional
    public FileResponse renameFile(
            UUID actorId,
            UUID fileId,
            String newName
    ) {
        String normalizedRequestedName =
                fileNameValidator.validateAndNormalize(newName);

        UUID ownerId = resourceReads.owner(false, fileId);
        hierarchyCoordinator.acquireShared(ownerId);

        StoredFile storedFile =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                fileId,
                                ownerId,
                                FileStatus.READY
                        )
                        .orElseThrow(() ->
                                new FileNotFoundException("File not found")
                        );

        resourceReads.refreshForMutation(storedFile);
        ResourceAccess permission = resourceReads.requireEditor(actorId, false, fileId, false);

        String resolvedName =
                fileNameExtensionResolver.preserveExtension(
                        storedFile.getName(),
                        normalizedRequestedName
                );

        String finalName =
                fileNameValidator.validateAndNormalize(resolvedName);

        validateFolderAccess(
                ownerId,
                storedFile.getFolderId()
        );
    
        Snapshot before = Snapshot.of(storedFile);
        StoredFile renamedFile =
                fileMetadataService.rename(
                        storedFile,
                        finalName
                );
        if (!before.equals(Snapshot.of(renamedFile))) {
            activity.append(actorId, RENAMED, before, Snapshot.of(renamedFile));
        }
    
        return toFileResponse(renamedFile, permission);
    }

    @Transactional
    public FileResponse moveFile(
            UUID ownerId,
            UUID fileId,
            UUID destinationFolderId
    ) {
        resourceReads.requireOwner(ownerId, false, fileId);
        hierarchyCoordinator.acquireShared(ownerId);

        StoredFile storedFile =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                fileId,
                                ownerId,
                                FileStatus.READY
                        )
                        .orElseThrow(() ->
                                new FileNotFoundException("File not found")
                        );

        // The source file must currently be accessible.
        validateFolderAccess(
                ownerId,
                storedFile.getFolderId()
        );

        // The destination must also be accessible.
        validateFolderAccess(
                ownerId,
                destinationFolderId
        );

        Snapshot before = Snapshot.of(storedFile);
        StoredFile movedFile =
                fileMetadataService.move(
                        storedFile,
                        destinationFolderId
                );
        if (!before.equals(Snapshot.of(movedFile))) {
            activity.append(ownerId, MOVED, before, Snapshot.of(movedFile));
        }
    
        return toFileResponse(movedFile);
    }

    @Transactional
    public FileResponse trashFile(
            UUID actorId,
            UUID fileId
    ) {
        UUID ownerId = resourceReads.owner(false, fileId);
        hierarchyCoordinator.acquireShared(ownerId);

        StoredFile storedFile =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                fileId,
                                ownerId,
                                FileStatus.READY
                        )
                        .orElseThrow(() ->
                                new FileNotFoundException("File not found")
                        );

        resourceReads.refreshForMutation(storedFile);
        ResourceAccess permission = resourceReads.requireEditor(actorId, false, fileId, true);

        validateFolderAccess(
                ownerId,
                storedFile.getFolderId()
        );

        Snapshot before = Snapshot.of(storedFile);
        StoredFile trashedFile =
                fileMetadataService.softDelete(storedFile);
        activity.append(actorId, TRASHED, before, Snapshot.of(trashedFile));
    
        return new FileResponse(trashedFile.getId(), trashedFile.getName(),
                permission.parentVisible() ? trashedFile.getFolderId() : null,
                trashedFile.getContentType(), trashedFile.getSizeBytes(), trashedFile.getCreatedAt(),
                trashedFile.getUpdatedAt(), permission.role(), java.util.List.of());
    }

    public com.vaultdrive.folder.dto.TrashPageResponse listTrash(UUID ownerId, int page, int size) {
        return resourceReads.trash(ownerId, false, page, size);
    }

    public FileResponse restoreFile(
            UUID ownerId,
            UUID fileId
    ) {
        resourceReads.requireOwner(ownerId, false, fileId);
        StoredFile restoredFile =
                fileMetadataService.restore(
                        ownerId,
                        fileId
                );
    
        return toFileResponse(restoredFile);
    }

    public UUID requestPermanentDeletion(
            UUID ownerId,
            UUID fileId
    ) {
        resourceReads.requireOwner(ownerId, false, fileId);
        return fileMetadataService.requestPermanentDeletion(
                ownerId,
                fileId
        );
    }

    private FileResponse toFileResponse(StoredFile file, ResourceAccess permission) {
        return new FileResponse(file.getId(), file.getName(),
                permission.role() == com.vaultdrive.sharing.ResourceRole.OWNER ? file.getFolderId()
                        : permission.parentVisible() ? permission.parentId() : null,
                file.getContentType(), file.getSizeBytes(), file.getCreatedAt(), file.getUpdatedAt(),
                permission.role(), permission.capabilities(false));
    }

    private FileResponse toFileResponse(
            StoredFile storedFile
    ) {
        return new FileResponse(
                storedFile.getId(),
                storedFile.getName(),
                storedFile.getFolderId(),
                storedFile.getContentType(),
                storedFile.getSizeBytes(),
                storedFile.getCreatedAt(),
                storedFile.getUpdatedAt()
        );
    }

    private void validateFolderAccess(
            UUID ownerId,
            UUID folderId
    ) {
        if (folderId == null) {
            return;
        }
    
        folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        );
    }

}
