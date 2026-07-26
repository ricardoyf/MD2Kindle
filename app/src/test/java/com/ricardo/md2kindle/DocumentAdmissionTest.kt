package com.ricardo.md2kindle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAdmissionTest {
    @Test
    fun `admite Markdown declarado desde content`() {
        assertTrue(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_VIEW,
                mimeType = "text/markdown",
                scheme = "content",
                displayName = "libro",
            ),
        )
    }

    @Test
    fun `admite octet stream solo si el nombre es compatible`() {
        assertTrue(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_SEND,
                mimeType = "application/octet-stream",
                scheme = "content",
                displayName = "Libro.MARKDOWN",
            ),
        )
        assertFalse(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_SEND,
                mimeType = "application/octet-stream",
                scheme = "content",
                displayName = "foto.jpg",
            ),
        )
        assertFalse(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_SEND,
                mimeType = "application/octet-stream",
                scheme = "content",
                displayName = null,
            ),
        )
    }

    @Test
    fun `admite file sin MIME por extension`() {
        assertTrue(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_VIEW,
                mimeType = null,
                scheme = "file",
                displayName = "/Download/notas.md",
            ),
        )
    }

    @Test
    fun `rechaza acciones esquemas y tipos irrelevantes`() {
        assertFalse(
            DocumentAdmission.acceptsUri(
                action = "android.intent.action.EDIT",
                mimeType = "text/markdown",
                scheme = "content",
                displayName = "libro.md",
            ),
        )
        assertFalse(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_VIEW,
                mimeType = "image/png",
                scheme = "content",
                displayName = "libro.md",
            ),
        )
        assertFalse(
            DocumentAdmission.acceptsUri(
                action = DocumentAdmission.ACTION_VIEW,
                mimeType = "text/markdown",
                scheme = "https",
                displayName = "libro.md",
            ),
        )
    }

    @Test
    fun `texto compartido solo se admite con send y MIME textual`() {
        assertTrue(
            DocumentAdmission.acceptsSharedText(
                DocumentAdmission.ACTION_SEND,
                "text/plain; charset=utf-8",
            ),
        )
        assertFalse(
            DocumentAdmission.acceptsSharedText(
                DocumentAdmission.ACTION_VIEW,
                "text/plain",
            ),
        )
        assertFalse(
            DocumentAdmission.acceptsSharedText(
                DocumentAdmission.ACTION_SEND,
                "application/octet-stream",
            ),
        )
    }
}
