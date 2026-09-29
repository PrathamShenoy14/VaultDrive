package com.vaultdrive.folder;

import com.vaultdrive.folder.exception.InvalidFolderNameException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class FolderNameValidatorTest {

    private final FolderNameValidator validator =
            new FolderNameValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "   "
    })
    void shouldRejectBlankNames(String name) {

        assertThatThrownBy(() ->
                validator.validateAndNormalize(name)
        ).isInstanceOf(InvalidFolderNameException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Projects/Backend",
            "Projects\\Backend",
            "Hello\u0000World",
            "Hello\nWorld"
    })
    void shouldRejectInvalidCharacters(String name) {

        assertThatThrownBy(() ->
                validator.validateAndNormalize(name)
        ).isInstanceOf(InvalidFolderNameException.class);
    }

    @Test
    void shouldRejectNamesExceeding255Characters() {

        String name = "A".repeat(256);

        assertThatThrownBy(() ->
                validator.validateAndNormalize(name)
        ).isInstanceOf(InvalidFolderNameException.class);
    }

    @Test
    void shouldAcceptExactly255Characters() {

        String name = "A".repeat(255);

        assertThat(
                validator.validateAndNormalize(name)
        ).isEqualTo(name);
    }

    @Test
    void shouldTrimLeadingAndTrailingWhitespace() {

        assertThat(
                validator.validateAndNormalize("  Documents  ")
        ).isEqualTo("Documents");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Documents",
            "My Projects",
            "Resume.pdf",
            "project_backend",
            "Project-2026",
            "documents"
    })
    void shouldAcceptValidNames(String name) {

        assertThat(
                validator.validateAndNormalize(name)
        ).isEqualTo(name);
    }

    @Test
    void shouldRejectTrailingControlCharacters() {
    
        assertThatThrownBy(() ->
                validator.validateAndNormalize("Documents\n")
        ).isInstanceOf(InvalidFolderNameException.class);
    
    }
}
