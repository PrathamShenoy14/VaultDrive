package com.vaultdrive.folder;

import com.vaultdrive.folder.exception.FolderNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolderAccessValidatorTest {

    @Mock
    private FolderRepository folderRepository;

    private FolderAccessValidator folderAccessValidator;

    @BeforeEach
    void setUp() {
        folderAccessValidator =
                new FolderAccessValidator(folderRepository);
    }

    @Test
    void shouldReturnAccessibleRootFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        Folder folder = mock(Folder.class);

        when(folder.getParentFolderId())
                .thenReturn(null);

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderId,
                        ownerId
                ))
                .thenReturn(Optional.of(folder));

        Folder result =
                folderAccessValidator.requireAccessibleFolder(
                        ownerId,
                        folderId
                );

        assertSame(folder, result);

        verify(folderRepository)
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderId,
                        ownerId
                );
    }

    @Test
    void shouldReturnAccessibleNestedFolderWhenAllAncestorsAreActive() {
        UUID ownerId = UUID.randomUUID();

        UUID documentsId = UUID.randomUUID();
        UUID projectsId = UUID.randomUUID();
        UUID javaId = UUID.randomUUID();

        Folder documents = mock(Folder.class);
        Folder projects = mock(Folder.class);
        Folder java = mock(Folder.class);

        when(java.getParentFolderId())
                .thenReturn(projectsId);

        when(projects.getParentFolderId())
                .thenReturn(documentsId);

        when(documents.getParentFolderId())
                .thenReturn(null);

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        javaId,
                        ownerId
                ))
                .thenReturn(Optional.of(java));

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        projectsId,
                        ownerId
                ))
                .thenReturn(Optional.of(projects));

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        documentsId,
                        ownerId
                ))
                .thenReturn(Optional.of(documents));

        Folder result =
                folderAccessValidator.requireAccessibleFolder(
                        ownerId,
                        javaId
                );

        assertSame(java, result);

        verify(folderRepository)
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        javaId,
                        ownerId
                );

        verify(folderRepository)
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        projectsId,
                        ownerId
                );

        verify(folderRepository)
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        documentsId,
                        ownerId
                );
    }

    @Test
    void shouldRejectFolderWhenFolderItselfIsDeletedOrMissing() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderId,
                        ownerId
                ))
                .thenReturn(Optional.empty());

        FolderNotFoundException exception =
                assertThrows(
                        FolderNotFoundException.class,
                        () -> folderAccessValidator
                                .requireAccessibleFolder(
                                        ownerId,
                                        folderId
                                )
                );

        assertEquals(
                "Folder not found",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFolderWhenParentIsDeletedOrMissing() {
        UUID ownerId = UUID.randomUUID();

        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        Folder child = mock(Folder.class);

        when(child.getParentFolderId())
                .thenReturn(parentId);

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        childId,
                        ownerId
                ))
                .thenReturn(Optional.of(child));

        /*
         * findBy...DeletedAtIsNull returns empty both when the
         * parent does not exist and when it has been soft deleted.
         */
        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        parentId,
                        ownerId
                ))
                .thenReturn(Optional.empty());

        FolderNotFoundException exception =
                assertThrows(
                        FolderNotFoundException.class,
                        () -> folderAccessValidator
                                .requireAccessibleFolder(
                                        ownerId,
                                        childId
                                )
                );

        assertEquals(
                "Parent folder not found",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFolderWhenHigherAncestorIsDeletedOrMissing() {
        UUID ownerId = UUID.randomUUID();

        UUID grandparentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        Folder child = mock(Folder.class);
        Folder parent = mock(Folder.class);

        when(child.getParentFolderId())
                .thenReturn(parentId);

        when(parent.getParentFolderId())
                .thenReturn(grandparentId);

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        childId,
                        ownerId
                ))
                .thenReturn(Optional.of(child));

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        parentId,
                        ownerId
                ))
                .thenReturn(Optional.of(parent));

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        grandparentId,
                        ownerId
                ))
                .thenReturn(Optional.empty());

        FolderNotFoundException exception =
                assertThrows(
                        FolderNotFoundException.class,
                        () -> folderAccessValidator
                                .requireAccessibleFolder(
                                        ownerId,
                                        childId
                                )
                );

        assertEquals(
                "Parent folder not found",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFolderBelongingToAnotherUser() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        /*
         * The repository query includes ownerId, so a folder owned
         * by another user appears exactly like a nonexistent folder.
         *
         * This is desirable because we do not leak whether another
         * user's folder exists.
         */
        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderId,
                        ownerId
                ))
                .thenReturn(Optional.empty());

        FolderNotFoundException exception =
                assertThrows(
                        FolderNotFoundException.class,
                        () -> folderAccessValidator
                                .requireAccessibleFolder(
                                        ownerId,
                                        folderId
                                )
                );

        assertEquals(
                "Folder not found",
                exception.getMessage()
        );
    }

    @Test
    void shouldDetectCycleInFolderHierarchy() {
        UUID ownerId = UUID.randomUUID();

        UUID folderAId = UUID.randomUUID();
        UUID folderBId = UUID.randomUUID();

        Folder folderA = mock(Folder.class);
        Folder folderB = mock(Folder.class);

        when(folderA.getParentFolderId())
                .thenReturn(folderBId);

        when(folderB.getParentFolderId())
                .thenReturn(folderAId);

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderAId,
                        ownerId
                ))
                .thenReturn(Optional.of(folderA));

        when(folderRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(
                        folderBId,
                        ownerId
                ))
                .thenReturn(Optional.of(folderB));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> folderAccessValidator
                                .requireAccessibleFolder(
                                        ownerId,
                                        folderAId
                                )
                );

        assertEquals(
                "Folder hierarchy contains a cycle",
                exception.getMessage()
        );
    }
}
