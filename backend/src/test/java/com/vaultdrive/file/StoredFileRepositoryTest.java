package com.vaultdrive.file;

import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
class StoredFileRepositoryTest {

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Test
    void shouldRejectSameRootNameWhenExistingFileIsUploading() {
        User owner = createUser("uploading@example.com");

        StoredFile existing = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        storedFileRepository.saveAndFlush(existing);

        StoredFile duplicate = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> storedFileRepository.saveAndFlush(duplicate)
        );
    }

    @Test
    void shouldRejectSameRootNameWhenExistingFileIsReady() {
        User owner = createUser("ready@example.com");

        StoredFile existing = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        existing.markReady();

        storedFileRepository.saveAndFlush(existing);

        StoredFile duplicate = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> storedFileRepository.saveAndFlush(duplicate)
        );
    }

    @Test
    void shouldAllowRetryWithSameNameWhenExistingFileIsFailed() {
        User owner = createUser("failed@example.com");

        StoredFile failed = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        failed.markFailed();

        storedFileRepository.saveAndFlush(failed);

        StoredFile retry = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        StoredFile savedRetry =
                storedFileRepository.saveAndFlush(retry);

        assertNotNull(savedRetry.getId());

        assertEquals(
                FileStatus.UPLOADING,
                savedRetry.getStatus()
        );

        assertNotEquals(
                failed.getId(),
                savedRetry.getId()
        );
    }

    @Test
    void shouldAllowSameNameForDifferentUsers() {
        User ownerOne =
                createUser("owner-one@example.com");

        User ownerTwo =
                createUser("owner-two@example.com");

        StoredFile first = createFile(
                ownerOne.getId(),
                null,
                "report.pdf"
        );

        StoredFile second = createFile(
                ownerTwo.getId(),
                null,
                "report.pdf"
        );

        storedFileRepository.saveAndFlush(first);

        assertDoesNotThrow(
                () -> storedFileRepository
                        .saveAndFlush(second)
        );
    }

    @Test
    void repositoryDuplicateQueryShouldIgnoreFailedFiles() {
        User owner =
                createUser("query-failed@example.com");

        StoredFile failed = createFile(
                owner.getId(),
                null,
                "retry.pdf"
        );

        failed.markFailed();

        storedFileRepository.saveAndFlush(failed);

        boolean exists =
                storedFileRepository
                        .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                                owner.getId(),
                                "retry.pdf",
                                Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                )
                        );

        assertFalse(exists);
    }

    @Test
    void repositoryDuplicateQueryShouldFindUploadingFiles() {
        User owner =
                createUser("query-uploading@example.com");

        StoredFile uploading = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        storedFileRepository.saveAndFlush(uploading);

        boolean exists =
                storedFileRepository
                        .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                                owner.getId(),
                                "report.pdf",
                                Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                )
                        );

        assertTrue(exists);
    }

    @Test
    void repositoryDuplicateQueryShouldFindReadyFiles() {
        User owner =
                createUser("query-ready@example.com");

        StoredFile ready = createFile(
                owner.getId(),
                null,
                "report.pdf"
        );

        ready.markReady();

        storedFileRepository.saveAndFlush(ready);

        boolean exists =
                storedFileRepository
                        .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                                owner.getId(),
                                "report.pdf",
                                Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                )
                        );

        assertTrue(exists);
    }

    @Test
    void shouldListOnlyReadyFilesInRequestedFolderForOwner() {
        User owner =
                createUser("listing-owner@example.com");

        User otherOwner =
                createUser("listing-other@example.com");

        Folder requestedFolder =
                createFolder(
                        owner.getId(),
                        "Documents"
                );

        Folder otherFolder =
                createFolder(
                        owner.getId(),
                        "Pictures"
                );

        Folder otherOwnerFolder =
                createFolder(
                        otherOwner.getId(),
                        "Documents"
                );

        StoredFile included =
                createFile(
                        owner.getId(),
                        requestedFolder.getId(),
                        "included.pdf"
                );
        included.markReady();

        StoredFile uploading =
                createFile(
                        owner.getId(),
                        requestedFolder.getId(),
                        "uploading.pdf"
                );

        StoredFile failed =
                createFile(
                        owner.getId(),
                        requestedFolder.getId(),
                        "failed.pdf"
                );
        failed.markFailed();

        StoredFile differentFolder =
                createFile(
                        owner.getId(),
                        otherFolder.getId(),
                        "other-folder.pdf"
                );
        differentFolder.markReady();

        StoredFile differentOwner =
                createFile(
                        otherOwner.getId(),
                        otherOwnerFolder.getId(),
                        "other-owner.pdf"
                );
        differentOwner.markReady();

        storedFileRepository.saveAllAndFlush(
                java.util.List.of(
                        included,
                        uploading,
                        failed,
                        differentFolder,
                        differentOwner
                )
        );

        Page<StoredFile> result =
                storedFileRepository
                        .findByOwnerIdAndFolderIdAndStatusAndDeletedAtIsNull(
                                owner.getId(),
                                requestedFolder.getId(),
                                FileStatus.READY,
                                PageRequest.of(
                                        0,
                                        50,
                                        Sort.by("name").ascending()
                                )
                        );

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(
                included.getId(),
                result.getContent().getFirst().getId()
        );
    }

    @Test
    void shouldListOnlyReadyRootFilesForOwner() {
        User owner =
                createUser("root-listing@example.com");

        Folder folder =
                createFolder(
                        owner.getId(),
                        "Documents"
                );

        StoredFile rootReady =
                createFile(
                        owner.getId(),
                        null,
                        "root.pdf"
                );
        rootReady.markReady();

        StoredFile rootUploading =
                createFile(
                        owner.getId(),
                        null,
                        "uploading.pdf"
                );

        StoredFile rootFailed =
                createFile(
                        owner.getId(),
                        null,
                        "failed.pdf"
                );
        rootFailed.markFailed();

        StoredFile nestedReady =
                createFile(
                        owner.getId(),
                        folder.getId(),
                        "nested.pdf"
                );
        nestedReady.markReady();

        storedFileRepository.saveAllAndFlush(
                java.util.List.of(
                        rootReady,
                        rootUploading,
                        rootFailed,
                        nestedReady
                )
        );

        Page<StoredFile> result =
                storedFileRepository
                        .findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
                                owner.getId(),
                                FileStatus.READY,
                                PageRequest.of(
                                        0,
                                        50,
                                        Sort.by("name").ascending()
                                )
                        );

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(
                rootReady.getId(),
                result.getContent().getFirst().getId()
        );
    }

    @Test
    void shouldPaginateAndSortReadyRootFilesByName() {
        User owner =
                createUser("pagination@example.com");

        StoredFile charlie =
                createFile(
                        owner.getId(),
                        null,
                        "charlie.pdf"
                );
        charlie.markReady();

        StoredFile alpha =
                createFile(
                        owner.getId(),
                        null,
                        "alpha.pdf"
                );
        alpha.markReady();

        StoredFile bravo =
                createFile(
                        owner.getId(),
                        null,
                        "bravo.pdf"
                );
        bravo.markReady();

        storedFileRepository.saveAllAndFlush(
                java.util.List.of(
                        charlie,
                        alpha,
                        bravo
                )
        );

        Page<StoredFile> firstPage =
                storedFileRepository
                        .findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
                                owner.getId(),
                                FileStatus.READY,
                                PageRequest.of(
                                        0,
                                        2,
                                        Sort.by("name").ascending()
                                )
                        );

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());

        assertEquals(
                "alpha.pdf",
                firstPage.getContent().get(0).getName()
        );

        assertEquals(
                "bravo.pdf",
                firstPage.getContent().get(1).getName()
        );

        Page<StoredFile> secondPage =
                storedFileRepository
                        .findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
                                owner.getId(),
                                FileStatus.READY,
                                PageRequest.of(
                                        1,
                                        2,
                                        Sort.by("name").ascending()
                                )
                        );

        assertEquals(3, secondPage.getTotalElements());
        assertEquals(2, secondPage.getTotalPages());
        assertEquals(1, secondPage.getContent().size());

        assertEquals(
                "charlie.pdf",
                secondPage.getContent().getFirst().getName()
        );
    }

    @Test
    void shouldFindReadyActiveFileForDownload() {
        User owner =
                createUser("download-ready@example.com");
    
        StoredFile readyFile =
                createFile(
                        owner.getId(),
                        null,
                        "report.pdf"
                );
    
        readyFile.markReady();
    
        storedFileRepository.saveAndFlush(readyFile);
    
        var result =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                readyFile.getId(),
                                owner.getId(),
                                FileStatus.READY
                        );
    
        assertTrue(result.isPresent());
    
        assertEquals(
                readyFile.getId(),
                result.get().getId()
        );
    }
    
    @Test
    void shouldNotFindUploadingFileForDownload() {
        User owner =
                createUser("download-uploading@example.com");
    
        StoredFile uploadingFile =
                createFile(
                        owner.getId(),
                        null,
                        "uploading.pdf"
                );
    
        storedFileRepository.saveAndFlush(
                uploadingFile
        );
    
        var result =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                uploadingFile.getId(),
                                owner.getId(),
                                FileStatus.READY
                        );
    
        assertTrue(result.isEmpty());
    }
    
    @Test
    void shouldNotFindFailedFileForDownload() {
        User owner =
                createUser("download-failed@example.com");
    
        StoredFile failedFile =
                createFile(
                        owner.getId(),
                        null,
                        "failed.pdf"
                );
    
        failedFile.markFailed();
    
        storedFileRepository.saveAndFlush(
                failedFile
        );
    
        var result =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                failedFile.getId(),
                                owner.getId(),
                                FileStatus.READY
                        );
    
        assertTrue(result.isEmpty());
    }
    
    @Test
    void shouldNotFindAnotherUsersFileForDownload() {
        User owner =
                createUser("download-owner@example.com");
    
        User otherUser =
                createUser("download-other@example.com");
    
        StoredFile readyFile =
                createFile(
                        owner.getId(),
                        null,
                        "private.pdf"
                );
    
        readyFile.markReady();
    
        storedFileRepository.saveAndFlush(
                readyFile
        );
    
        var result =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                readyFile.getId(),
                                otherUser.getId(),
                                FileStatus.READY
                        );
    
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldExcludeCurrentFileWhenCheckingNestedRenameDuplicate() {
        User user = createUser(
                "rename-self-nested@example.com"
        );
    
        Folder folder = createFolder(
                user.getId(),
                "Documents"
        );
    
        StoredFile file = createFile(
                user.getId(),
                folder.getId(),
                "report.pdf"
        );
    
        file.markReady();
        storedFileRepository.saveAndFlush(file);
    
        boolean exists =
                storedFileRepository
                        .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                                user.getId(),
                                folder.getId(),
                                "report.pdf",
                                Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                ),
                                file.getId()
                        );
    
        assertFalse(exists);
    }

    @Test
    void shouldFindAnotherFileWithSameNameForNestedRename() {
        User user = createUser(
                "rename-duplicate-nested@example.com"
        );
    
        Folder folder = createFolder(
                user.getId(),
                "Documents"
        );
    
        StoredFile firstFile = createFile(
                user.getId(),
                folder.getId(),
                "report.pdf"
        );
    
        firstFile.markReady();
        storedFileRepository.saveAndFlush(firstFile);
    
        StoredFile secondFile = createFile(
                user.getId(),
                folder.getId(),
                "invoice.pdf"
        );
    
        secondFile.markReady();
        storedFileRepository.saveAndFlush(secondFile);
    
        boolean exists =
                storedFileRepository
                        .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                                user.getId(),
                                folder.getId(),
                                "report.pdf",
                                Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                ),
                                secondFile.getId()
                        );
    
        assertTrue(exists);
    }

    @Test
    void shouldFindAnotherRootFileWithSameNameForRename() {
        User user = createUser(
                "rename-duplicate-root@example.com"
        );
    
        StoredFile firstFile = createFile(
                user.getId(),
                null,
                "report.pdf"
        );
    
        firstFile.markReady();
        storedFileRepository.saveAndFlush(firstFile);
    
        StoredFile secondFile = createFile(
                user.getId(),
                null,
                "invoice.pdf"
        );
    
        secondFile.markReady();
        storedFileRepository.saveAndFlush(secondFile);
    
        boolean exists =
                storedFileRepository
                        .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                                user.getId(),
                                "report.pdf",
                                Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                ),
                                secondFile.getId()
                        );
    
        assertTrue(exists);
    }

    @Test
    void shouldListOnlyReadyTrashedFilesForOwner() {
        User owner =
                createUser("trash-listing-owner@example.com");
    
        User otherOwner =
                createUser("trash-listing-other@example.com");
    
        Folder folder =
                createFolder(
                        owner.getId(),
                        "Documents"
                );
    
        // Should be included:
        // READY + deleted + correct owner + purge NOT requested
        StoredFile trashedReady =
                createFile(
                        owner.getId(),
                        folder.getId(),
                        "trashed.pdf"
                );
    
        trashedReady.markReady();
        trashedReady.softDelete();
    
        // Should NOT be included:
        // READY but still active
        StoredFile activeReady =
                createFile(
                        owner.getId(),
                        folder.getId(),
                        "active.pdf"
                );
    
        activeReady.markReady();
    
        // Should NOT be included:
        // FAILED + deleted
        StoredFile trashedFailed =
                createFile(
                        owner.getId(),
                        folder.getId(),
                        "failed.pdf"
                );
    
        trashedFailed.markFailed();
        trashedFailed.softDelete();
    
        // Should NOT be included:
        // another user's READY + deleted file
        StoredFile otherUsersTrashedFile =
                createFile(
                        otherOwner.getId(),
                        null,
                        "other.pdf"
                );
    
        otherUsersTrashedFile.markReady();
        otherUsersTrashedFile.softDelete();
    
        // Should NOT be included:
        // READY + deleted + correct owner,
        // but permanent deletion has already been requested
        StoredFile pendingPurgeFile =
                createFile(
                        owner.getId(),
                        folder.getId(),
                        "pending-purge.pdf"
                );
    
        pendingPurgeFile.markReady();
        pendingPurgeFile.softDelete();
        pendingPurgeFile.requestPermanentDeletion();
    
        storedFileRepository.saveAllAndFlush(
                java.util.List.of(
                        trashedReady,
                        activeReady,
                        trashedFailed,
                        otherUsersTrashedFile,
                        pendingPurgeFile
                )
        );
    
        Page<StoredFile> result =
                storedFileRepository
                        .findByOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                                owner.getId(),
                                FileStatus.READY,
                                PageRequest.of(
                                        0,
                                        50,
                                        Sort.by("name").ascending()
                                )
                        );
    
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
    
        StoredFile resultFile =
                result.getContent().getFirst();
    
        assertEquals(
                trashedReady.getId(),
                resultFile.getId()
        );
    
        assertEquals(
                "trashed.pdf",
                resultFile.getName()
        );
    
        assertNotNull(
                resultFile.getDeletedAt()
        );
    
        assertNull(
                resultFile.getPurgeRequestedAt()
        );
    
        assertFalse(
                result.getContent()
                        .stream()
                        .anyMatch(file ->
                                file.getId().equals(
                                        pendingPurgeFile.getId()
                                )
                        )
        );
    }

    @Test
    void shouldNotFindPendingPurgeFileForRestore() {
        User owner =
                createUser("pending-purge-restore@example.com");
    
        Folder folder =
                createFolder(
                        owner.getId(),
                        "Documents"
                );
    
        StoredFile file =
                createFile(
                        owner.getId(),
                        folder.getId(),
                        "report.pdf"
                );
    
        file.markReady();
        file.softDelete();
        file.requestPermanentDeletion();
    
        storedFileRepository.saveAndFlush(file);
    
        Optional<StoredFile> result =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                                file.getId(),
                                owner.getId(),
                                FileStatus.READY
                        );
    
        assertTrue(result.isEmpty());
    }

    private User createUser(String email) {
        User user = new User(
                email,
                "hashed-password",
                "Test User"
        );

        return userRepository.saveAndFlush(user);
    }

    private Folder createFolder(
            UUID ownerId,
            String name
    ) {
        Folder folder = new Folder(
                ownerId,
                null,
                name
        );

        return folderRepository.saveAndFlush(folder);
    }

    private StoredFile createFile(
            UUID ownerId,
            UUID folderId,
            String name
    ) {
        UUID fileId = UUID.randomUUID();

        return new StoredFile(
                fileId,
                ownerId,
                folderId,
                name,
                "users/"
                        + ownerId
                        + "/files/"
                        + fileId,
                "application/pdf",
                100L
        );
    }
}
