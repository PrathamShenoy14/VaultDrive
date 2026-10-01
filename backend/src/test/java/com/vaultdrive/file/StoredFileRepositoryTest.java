package com.vaultdrive.file;

import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

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
                                java.util.Set.of(
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
                                java.util.Set.of(
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
                                java.util.Set.of(
                                        FileStatus.UPLOADING,
                                        FileStatus.READY
                                )
                        );

        assertTrue(exists);
    }

    private User createUser(String email) {
        User user = new User(
                email,
                "hashed-password",
                "Test User"
        );

        return userRepository.saveAndFlush(user);
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
