package com.vaultdrive.file;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.dto.FileDownload;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.sharing.ResourceReadService;
import com.vaultdrive.sharing.ResourceAccess;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageObject;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FileService {

    private static final int MAX_PAGE_SIZE = 100;

    private final FolderAccessValidator folderAccessValidator;
    private final FileNameValidator fileNameValidator;
    private final FileMetadataService fileMetadataService;
    private final ObjectStorageService objectStorageService;
    private final StoredFileRepository storedFileRepository;
    private final FileNameExtensionResolver fileNameExtensionResolver;
    private final HierarchyCoordinator hierarchyCoordinator;
    private final ResourceReadService resourceReads;

    public FileService(
            FolderAccessValidator folderAccessValidator,
            FileNameValidator fileNameValidator,
            FileMetadataService fileMetadataService,
            ObjectStorageService objectStorageService,
            StoredFileRepository storedFileRepository,
            FileNameExtensionResolver fileNameExtensionResolver,
            HierarchyCoordinator hierarchyCoordinator,
            ResourceReadService resourceReads
    ) {
        this.folderAccessValidator = folderAccessValidator;
        this.fileNameValidator = fileNameValidator;
        this.fileMetadataService = fileMetadataService;
        this.objectStorageService = objectStorageService;
        this.storedFileRepository = storedFileRepository;
        this.fileNameExtensionResolver = fileNameExtensionResolver;
        this.hierarchyCoordinator = hierarchyCoordinator;
        this.resourceReads = resourceReads;
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
        StorageObject storageObject = objectStorageService.download(file.storageKey());
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
    
        StoredFile renamedFile =
                fileMetadataService.rename(
                        storedFile,
                        finalName
                );
    
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

        StoredFile movedFile =
                fileMetadataService.move(
                        storedFile,
                        destinationFolderId
                );
    
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

        StoredFile trashedFile =
                fileMetadataService.softDelete(storedFile);
    
        return toFileResponse(trashedFile, permission);
    }

    public FilePageResponse listTrash(
            UUID ownerId,
            int page,
            int size
    ) {
        validatePagination(page, size);
    
        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by("name").ascending()
                );
    
        Page<StoredFile> files =
                storedFileRepository
                        .findByOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                                ownerId,
                                FileStatus.READY,
                                pageable
                        );
    
        return new FilePageResponse(
                files.getContent()
                        .stream()
                        .map(this::toFileResponse)
                        .toList(),
                files.getNumber(),
                files.getSize(),
                files.getTotalElements(),
                files.getTotalPages()
        );
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

    public void requestPermanentDeletion(
            UUID ownerId,
            UUID fileId
    ) {
        resourceReads.requireOwner(ownerId, false, fileId);
        fileMetadataService.requestPermanentDeletion(
                ownerId,
                fileId
        );
    }

    private FileResponse toFileResponse(StoredFile file, ResourceAccess permission) {
        return new FileResponse(file.getId(), file.getName(),
                permission.role() == com.vaultdrive.sharing.ResourceRole.OWNER ? file.getFolderId()
                        : permission.parentVisible() ? permission.parentId() : null,
                file.getContentType(), file.getSizeBytes(), file.getCreatedAt(), file.getUpdatedAt(),
                permission.role(), permission.role().capabilities(false));
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

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 0) {
            throw new InvalidFilePaginationException(
                    "Page must be greater than or equal to 0"
            );
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidFilePaginationException(
                    "Size must be between 1 and "
                            + MAX_PAGE_SIZE
            );
        }
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
