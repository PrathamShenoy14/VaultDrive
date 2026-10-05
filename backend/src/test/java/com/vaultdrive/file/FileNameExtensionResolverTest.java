package com.vaultdrive.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vaultdrive.file.exception.FileExtensionChangeException;

class FileNameExtensionResolverTest {

    private final FileNameExtensionResolver resolver =
            new FileNameExtensionResolver();

    @Test
    void shouldPreserveExtensionWhenRequestedNameHasNoExtension() {
        String result =
                resolver.preserveExtension(
                        "Platform FDE - JD.pdf",
                        "Job Description"
                );

        assertEquals(
                "Job Description.pdf",
                result
        );
    }

    @Test
    void shouldNotDuplicateExtensionWhenRequestedNameAlreadyHasOne() {
        String result =
                resolver.preserveExtension(
                        "Platform FDE - JD.pdf",
                        "Job Description.pdf"
                );

        assertEquals(
                "Job Description.pdf",
                result
        );
    }

    @Test
    void shouldUseLastDotForFilesWithMultipleDots() {
        String result =
                resolver.preserveExtension(
                        "report.final.pdf",
                        "Final Report"
                );

        assertEquals(
                "Final Report.pdf",
                result
        );
    }

    @Test
    void shouldNotAddExtensionWhenOriginalFileHasNoExtension() {
        String result =
                resolver.preserveExtension(
                        "README",
                        "NOTES"
                );

        assertEquals(
                "NOTES",
                result
        );
    }

    @Test
    void shouldNotTreatLeadingDotAsExtension() {
        String result =
                resolver.preserveExtension(
                        ".env",
                        "config"
                );

        assertEquals(
                "config",
                result
        );
    }

    @Test
    void shouldNotTreatTrailingDotAsExtension() {
        String result =
                resolver.preserveExtension(
                        "document.",
                        "renamed"
                );

        assertEquals(
                "renamed",
                result
        );
    }

    @Test
    void shouldRejectChangingFileExtension() {
        FileExtensionChangeException exception =
                assertThrows(
                        FileExtensionChangeException.class,
                        () -> resolver.preserveExtension(
                                "report.pdf",
                                "report.txt"
                        )
                );
    
        assertEquals(
                "File extension cannot be changed",
                exception.getMessage()
        );
    }

    @Test
    void shouldAllowCaseDifferenceInSameExtension() {
        String result =
                resolver.preserveExtension(
                        "report.pdf",
                        "Final Report.PDF"
                );
    
        assertEquals(
                "Final Report.PDF",
                result
        );
    }
}