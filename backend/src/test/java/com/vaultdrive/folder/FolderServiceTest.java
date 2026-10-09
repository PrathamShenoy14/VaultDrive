package com.vaultdrive.folder;

import com.vaultdrive.folder.exception.DuplicateFolderNameException;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.folder.exception.InvalidFolderNameException;
import com.vaultdrive.folder.exception.InvalidFolderMoveException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.outbox.OutboxWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mock;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vaultdrive.folder.dto.FolderResponse;
import com.vaultdrive.folder.dto.TrashFolderResponse;
import com.vaultdrive.folder.dto.RestoreFolderResponse;

import java.util.List;

import com.vaultdrive.user.UserRepository;

import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FolderServiceTest {

    @Mock
   private FolderRepository folderRepository;
   
   @Mock
   private UserRepository userRepository;

   @Mock
   private HierarchyCoordinator hierarchyCoordinator;

   @Mock
   private OutboxWriter outboxWriter;

   private FolderService folderService;
   private FolderNameValidator folderNameValidator;
   private FolderAccessValidator folderAccessValidator;

    @BeforeEach
    void setUp() {

        folderNameValidator = new FolderNameValidator();
        folderAccessValidator = new FolderAccessValidator(folderRepository);

        folderService = new FolderService(
            folderRepository,
            folderAccessValidator,
            folderNameValidator,
            userRepository,
            hierarchyCoordinator,
            outboxWriter
        );

        lenient()
                .when(userRepository.existsById(any(UUID.class)))
                .thenReturn(true);
    }

    // TEST 1: Successfully create a root folder

    @Test
    void shouldCreateRootFolderSuccessfully() {

        UUID ownerId = UUID.randomUUID();

        when(
            folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                    ownerId,
                    "Documents"
                )
        ).thenReturn(false);

        when(folderRepository.saveAndFlush(any(Folder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID folderId = folderService.createFolder(
                ownerId,
                null,
                "Documents"
        );

        assertThat(folderId).isNotNull();

        verify(folderRepository).saveAndFlush(
                argThat(folder ->
                        folder.getId().equals(folderId)
                        && folder.getOwnerId().equals(ownerId)
                        && folder.getParentFolderId() == null
                        && folder.getName().equals("Documents")
                )
        );

        InOrder order = inOrder(
                hierarchyCoordinator,
                userRepository,
                folderRepository
        );

        order.verify(hierarchyCoordinator)
                .acquireExclusive(ownerId);
        order.verify(userRepository).existsById(ownerId);
        order.verify(folderRepository)
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                        ownerId,
                        "Documents"
                );
    }

    // TEST 2: Successfully create a nested folder

    @Test
    void shouldCreateNestedFolderSuccessfully() {

        UUID ownerId = UUID.randomUUID();

        Folder parentFolder = new Folder(
                ownerId,
                null,
                "Documents"
        );

        UUID parentFolderId = parentFolder.getId();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parentFolderId,
                ownerId
        )).thenReturn(Optional.of(parentFolder));

        when(
            folderRepository
                .existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
                    ownerId,
                    parentFolderId,
                    "Projects"
                )
        ).thenReturn(false);

        when(folderRepository.saveAndFlush(any(Folder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID folderId = folderService.createFolder(
                ownerId,
                parentFolderId,
                "Projects"
        );

        assertThat(folderId).isNotNull();

        verify(folderRepository).saveAndFlush(
                argThat(folder ->
                        folder.getOwnerId().equals(ownerId)
                        && folder.getParentFolderId().equals(parentFolderId)
                        && folder.getName().equals("Projects")
                )
        );
    }

    // TEST 3: Reject a parent folder owned by another user

    @Test
    void shouldRejectParentFolderOwnedByAnotherUser() {

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        Folder parentFolder = new Folder(
                bob,
                null,
                "Documents"
        );

        UUID parentFolderId = parentFolder.getId();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parentFolderId,
                alice
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.createFolder(
                        alice,
                        parentFolderId,
                        "Projects"
                )
        )
                .isInstanceOf(FolderNotFoundException.class)
                .hasMessage("Parent folder not found");

        verify(folderRepository, never())
                .saveAndFlush(any(Folder.class));
    }

    // TEST 4: Reject creation when an ancestor is deleted

    @Test
    void shouldRejectFolderCreationWhenAncestorIsDeleted() {

        UUID ownerId = UUID.randomUUID();

        Folder documents = new Folder(
                ownerId,
                null,
                "Documents"
        );

        Folder projects = new Folder(
                ownerId,
                documents.getId(),
                "Projects"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                projects.getId(),
                ownerId
        )).thenReturn(Optional.of(projects));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                documents.getId(),
                ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.createFolder(
                        ownerId,
                        projects.getId(),
                        "Photos"
                )
        )
                .isInstanceOf(FolderNotFoundException.class)
                .hasMessage("Parent folder not found");

        verify(folderRepository).findByIdAndOwnerIdAndDeletedAtIsNull(
                projects.getId(),
                ownerId
        );

        verify(folderRepository).findByIdAndOwnerIdAndDeletedAtIsNull(
                documents.getId(),
                ownerId
        );

        verify(folderRepository, never())
                .saveAndFlush(any(Folder.class));
    }

    // TEST 5: Reject duplicate root folder names

    @Test
    void shouldRejectDuplicateRootFolderName() {

        UUID ownerId = UUID.randomUUID();

        when(
            folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                    ownerId,
                    "Documents"
                )
        ).thenReturn(true);

        assertThatThrownBy(() ->
                folderService.createFolder(
                        ownerId,
                        null,
                        "Documents"
                )
        )
                .isInstanceOf(DuplicateFolderNameException.class)
                .hasMessage("A folder with this name already exists");

        verify(folderRepository, never())
                .saveAndFlush(any(Folder.class));
    }

    @Test
    void shouldRejectBlankFolderName() {
        UUID ownerId = UUID.randomUUID();

        assertThatThrownBy(() ->
                folderService.createFolder(ownerId, null, "   ")
        ).isInstanceOf(InvalidFolderNameException.class);

        verify(folderRepository, never())
                .saveAndFlush(any(Folder.class));
    }

    @Test
    void shouldRejectFolderNameExceeding255Characters() {
        UUID ownerId = UUID.randomUUID();
        String longName = "A".repeat(256);

        assertThatThrownBy(() ->
                folderService.createFolder(ownerId, null, longName)
        ).isInstanceOf(InvalidFolderNameException.class);

        verify(folderRepository, never())
                .saveAndFlush(any(Folder.class));
    }

    @Test
    void shouldTrimFolderNameBeforeSaving() {
        UUID ownerId = UUID.randomUUID();

        folderService.createFolder(
                ownerId,
                null,
                "  Documents  "
        );

        verify(folderRepository).saveAndFlush(
                argThat(folder ->
                        folder.getName().equals("Documents")
                )
        );
    }

    @Test
    void shouldDetectCycleInFolderHierarchy() {

        // Arrange
        UUID ownerId = UUID.randomUUID();

        UUID folderAId = UUID.randomUUID();
        UUID folderBId = UUID.randomUUID();

        Folder folderA = mock(Folder.class);
        Folder folderB = mock(Folder.class);

        // Simulate a corrupted hierarchy:
        // Folder A -> Folder B -> Folder A
        when(folderA.getParentFolderId()).thenReturn(folderBId);
        when(folderB.getParentFolderId()).thenReturn(folderAId);

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folderAId,
                ownerId
        )).thenReturn(Optional.of(folderA));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folderBId,
                ownerId
        )).thenReturn(Optional.of(folderB));

        // Act and Assert
        assertThatThrownBy(() ->
                folderService.createFolder(
                        ownerId,
                        folderAId,
                        "NewFolder"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Folder hierarchy contains a cycle");

        // Ensure nothing was saved.
        verify(folderRepository, never())
                .saveAndFlush(any(Folder.class));
    }

    @Test
    void shouldRetrieveFolderSuccessfully() {

        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(),
                ownerId
        )).thenReturn(Optional.of(folder));

        FolderResponse response = folderService.getFolder(
                ownerId,
                folder.getId()
        );

        assertThat(response.id()).isEqualTo(folder.getId());
        assertThat(response.name()).isEqualTo("Documents");
        assertThat(response.parentFolderId()).isNull();
    }

    @Test
    void shouldRejectRetrievingAnotherUsersFolder() {

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        Folder bobFolder = new Folder(bobId, null, "Private");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                bobFolder.getId(),
                aliceId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.getFolder(aliceId, bobFolder.getId())
        ).isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void shouldRejectRetrievingFolderWithDeletedAncestor() {

        UUID ownerId = UUID.randomUUID();

        Folder documents = new Folder(ownerId, null, "Documents");

        Folder projects = new Folder(
                ownerId,
                documents.getId(),
                "Projects"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                projects.getId(),
                ownerId
        )).thenReturn(Optional.of(projects));

        // Simulates Documents being soft-deleted.
        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                documents.getId(),
                ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.getFolder(ownerId, projects.getId())
        ).isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void shouldListRootFoldersSuccessfully() {

        UUID ownerId = UUID.randomUUID();

        Folder documents = new Folder(ownerId, null, "Documents");
        Folder pictures = new Folder(ownerId, null, "Pictures");

        when(folderRepository
                .findByOwnerIdAndParentFolderIdIsNullAndDeletedAtIsNull(ownerId)
        ).thenReturn(List.of(documents, pictures));

        List<FolderResponse> response =
                folderService.listRootFolders(ownerId);

        assertThat(response)
                .extracting(FolderResponse::name)
                .containsExactlyInAnyOrder("Documents", "Pictures");
    }

    @Test
    void shouldReturnEmptyListWhenNoRootFoldersExist() {

        UUID ownerId = UUID.randomUUID();

        when(folderRepository
                .findByOwnerIdAndParentFolderIdIsNullAndDeletedAtIsNull(ownerId)
        ).thenReturn(List.of());

        List<FolderResponse> response =
                folderService.listRootFolders(ownerId);

        assertThat(response).isEmpty();
    }

    @Test
    void shouldListImmediateChildrenAlphabetically() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");

        Folder zebra = new Folder(ownerId, parent.getId(), "Zebra");
        Folder apple = new Folder(ownerId, parent.getId(), "apple");
        Folder banana = new Folder(ownerId, parent.getId(), "Banana");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        when(folderRepository.findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(
                ownerId, parent.getId()
        )).thenReturn(List.of(zebra, apple, banana));

        List<FolderResponse> result =
                folderService.listChildFolders(ownerId, parent.getId());

        assertThat(result)
                .extracting(FolderResponse::name)
                .containsExactly("apple", "Banana", "Zebra");
    }

    @Test
    void shouldRejectListingAnotherUsersChildren() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        Folder bobFolder = new Folder(bobId, null, "Private");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                bobFolder.getId(), aliceId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.listChildFolders(aliceId, bobFolder.getId())
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never())
                .findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(
                        any(), any()
                );
    }

    @Test
    void shouldRejectListingChildrenWhenAncestorIsDeleted() {
        UUID ownerId = UUID.randomUUID();

        Folder documents = new Folder(ownerId, null, "Documents");
        Folder projects = new Folder(
                ownerId, documents.getId(), "Projects"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                projects.getId(), ownerId
        )).thenReturn(Optional.of(projects));

        // Documents is unavailable because it was deleted.
        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                documents.getId(), ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.listChildFolders(ownerId, projects.getId())
        ).isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void shouldReturnEmptyListForFolderWithoutChildren() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Empty");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        when(folderRepository.findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(
                ownerId, parent.getId()
        )).thenReturn(List.of());

        List<FolderResponse> result =
                folderService.listChildFolders(ownerId, parent.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void shouldSortNamesDifferingOnlyByCaseDeterministically() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");

        Folder lowercase = new Folder(
                ownerId, parent.getId(), "resume"
        );

        Folder uppercase = new Folder(
                ownerId, parent.getId(), "Resume"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        when(folderRepository.findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(
                ownerId, parent.getId()
        )).thenReturn(List.of(lowercase, uppercase));

        List<FolderResponse> result =
                folderService.listChildFolders(ownerId, parent.getId());

        assertThat(result)
                .extracting(FolderResponse::name)
                .containsExactly("Resume", "resume");
    }

    @Test
    void shouldRenameFolderSuccessfully() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        Instant originalCreatedAt = folder.getCreatedAt();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        FolderResponse response = folderService.renameFolder(
                ownerId, folder.getId(), "My Documents"
        );

        assertThat(response.name()).isEqualTo("My Documents");
        assertThat(response.id()).isEqualTo(folder.getId());
        assertThat(response.parentFolderId()).isNull();
        assertThat(response.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(response.updatedAt()).isAfterOrEqualTo(originalCreatedAt);

        verify(folderRepository).flush();
    }

    @Test
    void shouldRejectRenamingAnotherUsersFolder() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        Folder bobFolder = new Folder(bobId, null, "Private");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                bobFolder.getId(), aliceId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.renameFolder(
                        aliceId, bobFolder.getId(), "Renamed"
                )
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectDuplicateNameDuringRename() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        when(folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                        ownerId, "Pictures"
                )
        ).thenReturn(true);

        assertThatThrownBy(() ->
                folderService.renameFolder(
                        ownerId, folder.getId(), "Pictures"
                )
        ).isInstanceOf(DuplicateFolderNameException.class);

        assertThat(folder.getName()).isEqualTo("Documents");
        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldNotModifyFolderWhenNameIsUnchanged() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        Instant originalUpdatedAt = folder.getUpdatedAt();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        FolderResponse response = folderService.renameFolder(
                ownerId, folder.getId(), "Documents"
        );

        assertThat(response.name()).isEqualTo("Documents");
        assertThat(response.updatedAt()).isEqualTo(originalUpdatedAt);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectRenamingFolderWithDeletedAncestor() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.renameFolder(
                        ownerId, child.getId(), "Renamed Projects"
                )
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldNormalizeNameBeforeRenaming() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        FolderResponse response = folderService.renameFolder(
                ownerId, folder.getId(), "  Projects  "
        );

        assertThat(response.name()).isEqualTo("Projects");
        assertThat(folder.getName()).isEqualTo("Projects");
    }

    @Test
    void shouldMoveFolderSuccessfully() {
        UUID ownerId = UUID.randomUUID();

        Folder source = new Folder(ownerId, null, "Projects");
        Folder destination = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                source.getId(), ownerId
        )).thenReturn(Optional.of(source));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                destination.getId(), ownerId
        )).thenReturn(Optional.of(destination));

        FolderResponse result = folderService.moveFolder(
                ownerId, source.getId(), destination.getId()
        );

        assertThat(result.parentFolderId()).isEqualTo(destination.getId());
        assertThat(result.id()).isEqualTo(source.getId());

        verify(folderRepository).flush();
    }

    @Test
    void shouldMoveFolderToRoot() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        FolderResponse result = folderService.moveFolder(
                ownerId, child.getId(), null
        );

        assertThat(result.parentFolderId()).isNull();
        verify(folderRepository).flush();
    }

    @Test
    void shouldRejectMovingFolderIntoItself() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        assertThatThrownBy(() ->
                folderService.moveFolder(
                        ownerId, folder.getId(), folder.getId()
                )
        ).isInstanceOf(InvalidFolderMoveException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectMovingFolderIntoDescendant() {
        UUID ownerId = UUID.randomUUID();

        Folder documents = new Folder(ownerId, null, "Documents");
        Folder projects = new Folder(
                ownerId, documents.getId(), "Projects"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                documents.getId(), ownerId
        )).thenReturn(Optional.of(documents));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                projects.getId(), ownerId
        )).thenReturn(Optional.of(projects));

        assertThatThrownBy(() ->
                folderService.moveFolder(
                        ownerId, documents.getId(), projects.getId()
                )
        ).isInstanceOf(InvalidFolderMoveException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectMovingFolderIntoAnotherUsersFolder() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        Folder source = new Folder(aliceId, null, "Projects");
        Folder destination = new Folder(bobId, null, "Private");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                source.getId(), aliceId
        )).thenReturn(Optional.of(source));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                destination.getId(), aliceId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.moveFolder(
                        aliceId, source.getId(), destination.getId()
                )
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectMovingFolderWithDuplicateName() {
        UUID ownerId = UUID.randomUUID();

        Folder source = new Folder(ownerId, null, "Projects");
        Folder destination = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                source.getId(), ownerId
        )).thenReturn(Optional.of(source));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                destination.getId(), ownerId
        )).thenReturn(Optional.of(destination));

        when(folderRepository
                .existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
                        ownerId, destination.getId(), "Projects"
                )
        ).thenReturn(true);

        assertThatThrownBy(() ->
                folderService.moveFolder(
                        ownerId, source.getId(), destination.getId()
                )
        ).isInstanceOf(DuplicateFolderNameException.class);

        assertThat(source.getParentFolderId()).isNull();
        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldNotModifyFolderWhenDestinationIsUnchanged() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(
                ownerId, parent.getId(), "Projects"
        );

        Instant originalUpdatedAt = child.getUpdatedAt();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        FolderResponse result = folderService.moveFolder(
                ownerId, child.getId(), parent.getId()
        );

        assertThat(result.parentFolderId()).isEqualTo(parent.getId());
        assertThat(result.updatedAt()).isEqualTo(originalUpdatedAt);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectMovingFolderWithDeletedAncestor() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(
                ownerId, parent.getId(), "Projects"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        // Simulate the parent having been soft-deleted.
        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.moveFolder(
                        ownerId, child.getId(), null
                )
        ).isInstanceOf(FolderNotFoundException.class);

        assertThat(child.getParentFolderId()).isEqualTo(parent.getId());

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectMovingIntoDestinationWithDeletedAncestor() {
        UUID ownerId = UUID.randomUUID();

        Folder source = new Folder(ownerId, null, "Projects");

        Folder deletedParent = new Folder(
                ownerId, null, "Old Documents"
        );

        Folder destination = new Folder(
                ownerId,
                deletedParent.getId(),
                "Archive"
        );

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                source.getId(), ownerId
        )).thenReturn(Optional.of(source));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                destination.getId(), ownerId
        )).thenReturn(Optional.of(destination));

        // The destination exists, but its ancestor is unavailable.
        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                deletedParent.getId(), ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.moveFolder(
                        ownerId,
                        source.getId(),
                        destination.getId()
                )
        ).isInstanceOf(FolderNotFoundException.class);

        assertThat(source.getParentFolderId()).isNull();

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldSoftDeleteFolderSuccessfully() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        folderService.deleteFolder(ownerId, folder.getId());

        assertThat(folder.getDeletedAt()).isNotNull();
        assertThat(folder.getUpdatedAt()).isEqualTo(folder.getDeletedAt());

        verify(folderRepository).flush();
    }

    @Test
    void shouldRejectDeletingAnotherUsersFolder() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        Folder bobFolder = new Folder(bobId, null, "Private");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                bobFolder.getId(), aliceId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.deleteFolder(aliceId, bobFolder.getId())
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectDeletingAlreadyDeletedFolder() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");

        folder.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.deleteFolder(ownerId, folder.getId())
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectDeletingFolderWithDeletedAncestor() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.deleteFolder(ownerId, child.getId())
        ).isInstanceOf(FolderNotFoundException.class);

        assertThat(child.getDeletedAt()).isNull();

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldNotDirectlyDeleteDescendants() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        folderService.deleteFolder(ownerId, parent.getId());

        assertThat(parent.getDeletedAt()).isNotNull();
        assertThat(child.getDeletedAt()).isNull();

        verify(folderRepository).flush();
        verify(folderRepository, never()).delete(any(Folder.class));
    }

    @Test
    void shouldListTrashedFoldersNewestFirst() {
        UUID ownerId = UUID.randomUUID();

        Folder documents = new Folder(ownerId, null, "Documents");
        Folder pictures = new Folder(ownerId, null, "Pictures");

        documents.softDelete();
        pictures.softDelete();

        // Assign deterministic deletion timestamps.
        ReflectionTestUtils.setField(
                documents,
                "deletedAt",
                Instant.parse("2026-09-20T10:00:00Z")
        );

        ReflectionTestUtils.setField(
                pictures,
                "deletedAt",
                Instant.parse("2026-09-21T10:00:00Z")
        );

        when(folderRepository
                .findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        ownerId
                ))
                .thenReturn(List.of(documents, pictures));

        List<TrashFolderResponse> result =
                folderService.listTrashedFolders(ownerId);

        assertThat(result)
                .extracting(TrashFolderResponse::id)
                .containsExactly(
                        pictures.getId(),
                        documents.getId()
                );

        assertThat(result.get(0).deletedAt())
                .isAfter(result.get(1).deletedAt());
    }

    @Test
    void shouldReturnEmptyTrashList() {
        UUID ownerId = UUID.randomUUID();

        when(folderRepository
                .findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        ownerId
                ))
                .thenReturn(List.of());

        List<TrashFolderResponse> result =
                folderService.listTrashedFolders(ownerId);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldPreserveOriginalParentInTrashResponse() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");

        child.softDelete();

        when(folderRepository
                .findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        ownerId
                ))
                .thenReturn(List.of(child));

        List<TrashFolderResponse> result =
                folderService.listTrashedFolders(ownerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).originalParentFolderId())
                .isEqualTo(parent.getId());
        assertThat(result.get(0).deletedAt()).isNotNull();
    }

    @Test
    void shouldRestoreDeletedRootFolder() {
        UUID ownerId = UUID.randomUUID();

        Folder folder = new Folder(ownerId, null, "Documents");
        folder.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        when(folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                        ownerId, "Documents"
                )).thenReturn(false);

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, folder.getId());

        assertThat(response.folderId()).isEqualTo(folder.getId());
        assertThat(response.restoredName()).isEqualTo("Documents");
        assertThat(response.parentFolderId()).isNull();
        assertThat(folder.getDeletedAt()).isNull();

        verify(folderRepository).flush();
    }

    @Test
    void shouldRestoreFolderToOriginalActiveParent() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");
        child.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, child.getId());

        assertThat(response.parentFolderId()).isEqualTo(parent.getId());
        assertThat(response.restoredName()).isEqualTo("Projects");
        assertThat(child.getDeletedAt()).isNull();

        verify(folderRepository).flush();
    }

    @Test
    void shouldRestoreFolderToRootWhenOriginalParentIsDeleted() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");

        parent.softDelete();
        child.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        // Active-parent lookup fails because the parent is deleted.
        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.empty());

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, child.getId());

        assertThat(response.parentFolderId()).isNull();
        assertThat(child.getParentFolderId()).isNull();
        assertThat(child.getDeletedAt()).isNull();

        verify(folderRepository).flush();
    }

    @Test
    void shouldRejectChildRestoreBelowPendingPurgeAncestor() {
        UUID ownerId = UUID.randomUUID();
        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");
        parent.softDelete();
        parent.requestPermanentDeletion();
        child.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));
        when(folderRepository.hasPendingPurgeInAncestry(
                ownerId, parent.getId()
        )).thenReturn(true);

        assertThatThrownBy(() ->
                folderService.restoreFolder(ownerId, child.getId())
        ).isInstanceOf(FolderNotFoundException.class);

        assertThat(child.getDeletedAt()).isNotNull();
        assertThat(child.getParentFolderId()).isEqualTo(parent.getId());
        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRequestPermanentDeletionForEligibleTrashedFolder() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");
        folder.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));
        when(folderRepository.hasPendingPurgeInSubtree(
                ownerId, folder.getId()
        )).thenReturn(false);

        folderService.requestPermanentDeletion(ownerId, folder.getId());

        assertThat(folder.getPurgeRequestedAt()).isNotNull();
        assertThat(folder.getUpdatedAt())
                .isEqualTo(folder.getPurgeRequestedAt());
        verify(hierarchyCoordinator).acquireExclusive(ownerId);
        verify(folderRepository).flush();
        verify(outboxWriter).append(argThat(event ->
                event.getEventType().equals("FOLDER_PURGE_REQUESTED")
                        && event.getAggregateId().equals(folder.getId())
                        && event.getPayloadVersion() == 1
                        && event.getPayload().get("ownerId").equals(ownerId.toString())
                        && event.getCreatedAt().equals(folder.getPurgeRequestedAt())
        ));
    }

    @Test
    void shouldRejectPermanentDeletionBelowPendingPurgeAncestor() {
        UUID ownerId = UUID.randomUUID();
        Folder parent = new Folder(ownerId, null, "Documents");
        Folder child = new Folder(ownerId, parent.getId(), "Projects");
        child.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));
        when(folderRepository.hasPendingPurgeInAncestry(
                ownerId, parent.getId()
        )).thenReturn(true);

        assertThatThrownBy(() ->
                folderService.requestPermanentDeletion(
                        ownerId,
                        child.getId()
                )
        ).isInstanceOf(FolderNotFoundException.class);

        assertThat(child.getPurgeRequestedAt()).isNull();
        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectPermanentDeletionAbovePendingPurgeDescendant() {
        UUID ownerId = UUID.randomUUID();
        Folder parent = new Folder(ownerId, null, "Documents");
        parent.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));
        when(folderRepository.hasPendingPurgeInSubtree(
                ownerId, parent.getId()
        )).thenReturn(true);

        assertThatThrownBy(() ->
                folderService.requestPermanentDeletion(
                        ownerId,
                        parent.getId()
                )
        ).isInstanceOf(FolderNotFoundException.class);

        assertThat(parent.getPurgeRequestedAt()).isNull();
        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectRepeatedPermanentFolderDeletionRequest() {
        UUID ownerId = UUID.randomUUID();
        Folder folder = new Folder(ownerId, null, "Documents");
        folder.softDelete();
        folder.requestPermanentDeletion();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        assertThatThrownBy(() ->
                folderService.requestPermanentDeletion(
                        ownerId,
                        folder.getId()
                )
        ).isInstanceOf(FolderNotFoundException.class);

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectInaccessiblePermanentFolderDeletionAfterLock() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folderId, ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.requestPermanentDeletion(ownerId, folderId)
        )
                .isInstanceOf(FolderNotFoundException.class)
                .hasMessage("Deleted folder not found");

        InOrder order = inOrder(
                hierarchyCoordinator,
                userRepository,
                folderRepository
        );
        order.verify(hierarchyCoordinator).acquireExclusive(ownerId);
        order.verify(userRepository).existsById(ownerId);
        order.verify(folderRepository)
                .findByIdAndOwnerIdAndDeletedAtIsNotNull(
                        folderId,
                        ownerId
                );
        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectRestoringAnotherUsersFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folderId, ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.restoreFolder(ownerId, folderId)
        ).isInstanceOf(FolderNotFoundException.class)
         .hasMessage("Deleted folder not found");

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRejectRestoringActiveFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folderId, ownerId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                folderService.restoreFolder(ownerId, folderId)
        ).isInstanceOf(FolderNotFoundException.class)
         .hasMessage("Deleted folder not found");

        verify(folderRepository, never()).flush();
    }

    @Test
    void shouldRenameRestoredFolderWhenNameAlreadyExists() {
        UUID ownerId = UUID.randomUUID();

        Folder folder = new Folder(ownerId, null, "Documents");
        folder.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        when(folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                        ownerId, "Documents"
                )).thenReturn(true);

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, folder.getId());

        assertThat(response.restoredName())
                .isEqualTo("Documents (restored)");

        assertThat(folder.getName())
                .isEqualTo("Documents (restored)");

        assertThat(folder.getDeletedAt()).isNull();

        verify(folderRepository).flush();
    }

    @Test
    void shouldGenerateNumberedRestoredName() {
        UUID ownerId = UUID.randomUUID();

        Folder folder = new Folder(ownerId, null, "Documents");
        folder.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        when(folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                        eq(ownerId), anyString()
                ))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(1);

                    return name.equals("Documents")
                            || name.equals("Documents (restored)");
                });

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, folder.getId());

        assertThat(response.restoredName())
                .isEqualTo("Documents (restored 2)");

        verify(folderRepository).flush();
    }

    @Test
    void shouldTruncateLongFolderNameDuringRestoration() {
        UUID ownerId = UUID.randomUUID();

        String originalName = "A".repeat(255);

        Folder folder = new Folder(ownerId, null, originalName);
        folder.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                folder.getId(), ownerId
        )).thenReturn(Optional.of(folder));

        when(folderRepository
                .existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
                        ownerId, originalName
                )).thenReturn(true);

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, folder.getId());

        assertThat(response.restoredName()).hasSize(255);
        assertThat(response.restoredName())
                .endsWith(" (restored)");

        assertThat(folder.getDeletedAt()).isNull();

        verify(folderRepository).flush();
    }

    @Test
    void shouldRestoreToRootWhenGrandparentIsDeleted() {
        UUID ownerId = UUID.randomUUID();

        Folder grandparent = new Folder(ownerId, null, "Documents");

        Folder parent = new Folder(
                ownerId,
                grandparent.getId(),
                "Projects"
        );

        Folder child = new Folder(
                ownerId,
                parent.getId(),
                "VaultDrive"
        );

        grandparent.softDelete();
        child.softDelete();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                child.getId(), ownerId
        )).thenReturn(Optional.of(child));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNull(
                grandparent.getId(), ownerId
        )).thenReturn(Optional.empty());

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, child.getId());

        assertThat(response.parentFolderId()).isNull();
        assertThat(child.getParentFolderId()).isNull();
        assertThat(child.getDeletedAt()).isNull();

        verify(folderRepository).flush();
    }

    @Test
    void shouldNotModifyIndependentlyDeletedDescendants() {
        UUID ownerId = UUID.randomUUID();

        Folder parent = new Folder(ownerId, null, "Documents");

        Folder child = new Folder(
                ownerId,
                parent.getId(),
                "Projects"
        );

        parent.softDelete();
        child.softDelete();

        Instant childDeletionTime = child.getDeletedAt();

        when(folderRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(
                parent.getId(), ownerId
        )).thenReturn(Optional.of(parent));

        folderService.restoreFolder(ownerId, parent.getId());

        assertThat(parent.getDeletedAt()).isNull();

        assertThat(child.getDeletedAt())
                .isEqualTo(childDeletionTime);

        verify(folderRepository).flush();
    }
}
