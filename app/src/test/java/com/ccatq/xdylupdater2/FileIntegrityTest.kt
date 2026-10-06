package com.ccatq.xdylupdater2

import com.ccatq.xdylupdater2.downloads.FileIntegrity
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class FileIntegrityTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun successfulVerificationMovesFileAtomically() {
        val staging = temp.newFile("a.partial").apply { writeText("abc") }
        val destination = File(temp.root, "completed/file.jar")
        FileIntegrity.commit(staging, destination, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertFalse(staging.exists()); assertEquals("abc", destination.readText())
    }
    @Test fun mismatchNeverProducesACompletedFile() {
        val staging = temp.newFile("bad.partial").apply { writeText("bad") }
        val destination = File(temp.root, "completed/file.jar")
        try { FileIntegrity.commit(staging, destination, "0".repeat(64)); fail("Expected hash mismatch") } catch (_: IllegalArgumentException) { }
        assertTrue(staging.exists()); assertFalse(destination.exists())
    }
    @Test fun missingHashStillSavesTheFileAndInvalidHashFails() {
        val staging = temp.newFile("plain.partial").apply { writeText("plain") }
        val destination = File(temp.root, "completed/plain.txt")
        FileIntegrity.commit(staging, destination, null); assertTrue(destination.isFile)
        val bad = temp.newFile("invalid.partial")
        try { FileIntegrity.commit(bad, File(temp.root, "bad"), "abc"); fail("Expected invalid hash") } catch (_: IllegalArgumentException) { }
    }
    @Test fun cancellationBeforeCommitPreservesStagingAndPublishesNothing() {
        val staging = temp.newFile("cancelled.partial").apply { writeText("abc") }
        val destination = File(temp.root, "completed/cancelled.jar")
        try {
            FileIntegrity.commit(staging, destination, null) { throw java.util.concurrent.CancellationException() }
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertTrue(staging.exists()); assertFalse(destination.exists())
    }
    @Test fun unsafeNamesCannotEscapeTheDownloadDirectory() {
        assertEquals("mod.jar", FileIntegrity.safeName("../../mod.jar"))
        assertEquals("resource.bin", FileIntegrity.safeName(".."))
        assertEquals("mod.jar", FileIntegrity.safeName("C:\\path\\mod.jar"))
        assertFalse(FileIntegrity.safeName("bad\u0000name:*.jar").contains('\u0000'))
    }
}
