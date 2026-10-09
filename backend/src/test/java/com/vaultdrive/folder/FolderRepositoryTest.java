package com.vaultdrive.folder;

import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
class FolderRepositoryTest {

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User createUser() {

        User user = new User(
                UUID.randomUUID() + "@example.com",
                "temporary-test-hash",
                "Test User"
        );

        return userRepository.saveAndFlush(user);
    }

    // TEST 1: Reject duplicate root folder names

    @Test
    void shouldRejectDuplicateRootFolderNames() {

        User alice = createUser();

        Folder firstFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        Folder secondFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        folderRepository.saveAndFlush(firstFolder);

        assertThatThrownBy(() ->
                folderRepository.saveAndFlush(secondFolder)
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );
    }

    // TEST 2: Allow identical names for different users

    @Test
    void shouldAllowIdenticalFolderNamesForDifferentUsers() {

        User alice = createUser();
        User bob = createUser();

        Folder aliceFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        Folder bobFolder = new Folder(
                bob.getId(),
                null,
                "Documents"
        );

        folderRepository.saveAndFlush(aliceFolder);
        folderRepository.saveAndFlush(bobFolder);

        assertThat(folderRepository.findById(aliceFolder.getId()))
                .isPresent();

        assertThat(folderRepository.findById(bobFolder.getId()))
                .isPresent();
    }

    // TEST 3: Allow identical names under different parents

    @Test
    void shouldAllowIdenticalNamesUnderDifferentParents() {

        User alice = createUser();

        Folder documents = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        Folder pictures = new Folder(
                alice.getId(),
                null,
                "Pictures"
        );

        folderRepository.saveAndFlush(documents);
        folderRepository.saveAndFlush(pictures);

        Folder firstProjects = new Folder(
                alice.getId(),
                documents.getId(),
                "Projects"
        );

        Folder secondProjects = new Folder(
                alice.getId(),
                pictures.getId(),
                "Projects"
        );

        folderRepository.saveAndFlush(firstProjects);
        folderRepository.saveAndFlush(secondProjects);

        assertThat(folderRepository.findById(firstProjects.getId()))
                .isPresent();

        assertThat(folderRepository.findById(secondProjects.getId()))
                .isPresent();
    }

    // TEST 4: Reject a parent folder owned by another user

    @Test
    void shouldRejectCrossUserParentReference() {

        User alice = createUser();
        User bob = createUser();

        Folder bobFolder = new Folder(
                bob.getId(),
                null,
                "Private"
        );

        folderRepository.saveAndFlush(bobFolder);

        Folder unauthorizedChild = new Folder(
                alice.getId(),
                bobFolder.getId(),
                "Unauthorized"
        );

        assertThatThrownBy(() ->
                folderRepository.saveAndFlush(unauthorizedChild)
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );
    }

    // TEST 5: Reject a folder referencing itself as its parent

    @Test
    void shouldRejectFolderReferencingItself() {

        User alice = createUser();

        Folder folder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        /*
         * Our Folder entity intentionally has no public setter
         * for parentFolderId.
         *
         * We use native SQL here to test the database constraint
         * directly without weakening our entity design.
         */

        folderRepository.saveAndFlush(folder);

        assertThatThrownBy(() -> {

            entityManager.createNativeQuery("""
                    UPDATE folders
                    SET parent_folder_id = :folderId
                    WHERE id = :folderId
                    """)
                    .setParameter("folderId", folder.getId())
                    .executeUpdate();

            entityManager.flush();

        }).isInstanceOf(Exception.class);
    }

    // TEST 6: Allow name reuse after soft deletion

    @Test
    void shouldAllowNameReuseAfterSoftDeletion() {

        User alice = createUser();

        Folder originalFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        folderRepository.saveAndFlush(originalFolder);

        /*
         * Simulate soft deletion directly in SQL.
         * We'll implement the actual delete service later.
         */

        entityManager.createNativeQuery("""
                UPDATE folders
                SET deleted_at = :deletedAt
                WHERE id = :folderId
                """)
                .setParameter("deletedAt", Instant.now())
                .setParameter("folderId", originalFolder.getId())
                .executeUpdate();

        entityManager.flush();

        Folder replacementFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );

        folderRepository.saveAndFlush(replacementFolder);

        assertThat(folderRepository.findById(replacementFolder.getId()))
                .isPresent();
    }

    // TEST 7: Return only deleted folders belonging to the owner
    
    @Test
    void shouldReturnOnlyDeletedFoldersBelongingToOwner() {
    
        User alice = createUser();
        User bob = createUser();
    
        Folder deletedAliceFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );
    
        deletedAliceFolder.softDelete();
    
        Folder activeAliceFolder = new Folder(
                alice.getId(),
                null,
                "Pictures"
        );
    
        Folder deletedBobFolder = new Folder(
                bob.getId(),
                null,
                "Private"
        );
    
        deletedBobFolder.softDelete();
    
        folderRepository.saveAllAndFlush(
                List.of(
                        deletedAliceFolder,
                        activeAliceFolder,
                        deletedBobFolder
                )
        );
    
        List<Folder> result =
                folderRepository.findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        alice.getId()
                );
    
        assertThat(result)
                .extracting(Folder::getId)
                .containsExactly(deletedAliceFolder.getId());
    }
    
    
    // TEST 8: Return an empty list when the owner has no deleted folders
    
    @Test
    void shouldReturnEmptyListWhenOwnerHasNoDeletedFolders() {
    
        User alice = createUser();
    
        Folder activeFolder = new Folder(
                alice.getId(),
                null,
                "Documents"
        );
    
        folderRepository.saveAndFlush(activeFolder);
    
        List<Folder> result =
                folderRepository.findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        alice.getId()
                );
    
        assertThat(result).isEmpty();
    }
    
    
    // TEST 9: Return independently deleted child folders
    
    @Test
    void shouldReturnIndependentlyDeletedChildFolder() {
    
        User alice = createUser();
    
        Folder parent = new Folder(
                alice.getId(),
                null,
                "Documents"
        );
    
        folderRepository.saveAndFlush(parent);
    
        Folder child = new Folder(
                alice.getId(),
                parent.getId(),
                "Projects"
        );
    
        child.softDelete();
    
        folderRepository.saveAndFlush(child);
    
        List<Folder> result =
                folderRepository.findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        alice.getId()
                );
    
        assertThat(result)
                .extracting(Folder::getId)
                .containsExactly(child.getId());
    
        assertThat(result.get(0).getParentFolderId())
                .isEqualTo(parent.getId());
    }
    
    
    // TEST 10: Do not list active descendants of a deleted parent
    
    @Test
    void shouldNotIncludeActiveDescendantsOfDeletedParent() {
    
        User alice = createUser();
    
        Folder parent = new Folder(
                alice.getId(),
                null,
                "Documents"
        );
    
        folderRepository.saveAndFlush(parent);
    
        Folder child = new Folder(
                alice.getId(),
                parent.getId(),
                "Projects"
        );
    
        folderRepository.saveAndFlush(child);
    
        // Explicitly delete the parent.
        parent.softDelete();
    
        folderRepository.saveAndFlush(parent);
    
        // Clear the persistence context to force fresh database reads.
        entityManager.clear();
    
        // Verify that the deletion was actually persisted.
        Folder persistedParent = folderRepository
                .findById(parent.getId())
                .orElseThrow();
    
        assertThat(persistedParent.getDeletedAt())
                .isNotNull();
    
        // Verify that the child was not explicitly deleted.
        Folder persistedChild = folderRepository
                .findById(child.getId())
                .orElseThrow();
    
        assertThat(persistedChild.getDeletedAt())
                .isNull();
    
        // Execute the actual repository query.
        List<Folder> result =
                folderRepository.findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        alice.getId()
                );
    
        assertThat(result)
                .extracting(Folder::getId)
                .containsExactly(parent.getId());
    }

    @Test
    void shouldFindPendingPurgeAcrossFolderHierarchyAndHideItFromTrash() {
        User owner = createUser();
        Folder parent = new Folder(owner.getId(), null, "Documents");
        Folder child = new Folder(
                owner.getId(),
                parent.getId(),
                "Projects"
        );
        parent.softDelete();
        parent.requestPermanentDeletion();
        child.softDelete();
        folderRepository.saveAllAndFlush(List.of(parent, child));
        entityManager.clear();

        assertThat(folderRepository.hasPendingPurgeInAncestry(
                owner.getId(),
                child.getId()
        )).isTrue();
        assertThat(folderRepository.hasPendingPurgeInSubtree(
                owner.getId(),
                parent.getId()
        )).isTrue();
        assertThat(folderRepository
                .findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        owner.getId()
                ))
                .extracting(Folder::getId)
                .containsExactly(child.getId());
    }

    @Test
    void shouldRejectPurgeRequestWithoutSoftDeletion() {
        User owner = createUser();
        Folder folder = folderRepository.saveAndFlush(
                new Folder(owner.getId(), null, "Documents")
        );

        folder.requestPermanentDeletion();

        assertThatThrownBy(folderRepository::flush)
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
