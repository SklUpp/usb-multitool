package usb.files

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull

class FilesOperationPolicyTest {
    @Test fun validatesSafNames() {
        assertEquals("image.iso", FilesOperationPolicy.requireChildName("dir/image.iso", "content://x"))
        assertThrows(IllegalArgumentException::class.java) { FilesOperationPolicy.requireChildName("..", "content://x") }
    }

    @Test fun mapsMutationMessages() {
        assertEquals("/x already exists: delete it first (delete asks for confirmation)", FilesOperationPolicy.duplicateMessage("/x"))
        assertEquals("Cannot delete \"d\": directory is not empty — remove its contents first", FilesOperationPolicy.deleteError("d", "/d", "Directory not empty"))
        assertNull(FilesOperationPolicy.deleteError("d", "/d", "other"))
    }
}
