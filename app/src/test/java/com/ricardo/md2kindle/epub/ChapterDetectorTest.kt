package com.ricardo.md2kindle.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterDetectorTest {
    private val detector = ChapterDetector()

    @Test
    fun `crea jerarquia de partes y capitulos y excluye metadatos y mapa manual`() {
        val markdown = """
            # Libro

            ## Subtítulo

            ### Audiolibro para escuchar

            ## Mapa de escucha

            - [Nota](#nota)

            ## Nota al oyente

            Texto.

            ## Prólogo

            Texto.

            ## Primera parte

            ### 1. Primer capítulo

            #### Detalle interno

            ### 2. Segundo capítulo

            ## Segunda parte

            ### 3. Tercer capítulo

            ## Epílogo

            ## Guía de fuentes

            ### Correspondencia temática

            ### Claves de contraste
        """.trimIndent()

        val structure = detector.detectBook(markdown, "Libro")
        assertEquals(
            listOf(
                "Nota al oyente" to 1,
                "Prólogo" to 1,
                "Primera parte" to 1,
                "1. Primer capítulo" to 2,
                "2. Segundo capítulo" to 2,
                "Segunda parte" to 1,
                "3. Tercer capítulo" to 2,
                "Epílogo" to 1,
                "Guía de fuentes" to 1,
            ),
            structure.chapters.map { it.title to it.tocLevel },
        )
        assertTrue(structure.frontMatterMarkdown.orEmpty().contains("# Libro"))
        assertFalse(structure.frontMatterMarkdown.orEmpty().contains("Mapa de escucha"))
        assertTrue(structure.chapters.last().markdown.contains("Correspondencia temática"))
    }

    @Test
    fun `reconoce capitulos sin almohadilla cuando estan aislados`() {
        val chapters = detector.detect(
            """
                Capítulo 1. Comienzo

                Texto uno.

                Capítulo 2. Continuación

                Texto dos.
            """.trimIndent(),
            "Libro",
        )

        assertEquals(listOf("Capítulo 1. Comienzo", "Capítulo 2. Continuación"), chapters.map { it.title })
    }

    @Test
    fun `genera identificadores ascii estables y unicos`() {
        val chapters = detector.detect(
            """
                # Prólogo: la razón

                Uno.

                # Prólogo: la razón

                Dos.
            """.trimIndent(),
            "Libro",
        )

        assertEquals(listOf("prologo-la-razon", "prologo-la-razon-2"), chapters.map { it.anchorId })
    }

    @Test
    fun `archivo real produce veinticinco entradas cuando se facilita por entorno`() {
        val path = System.getenv("MD2KINDLE_REAL_MD") ?: return
        val file = File(path)
        assertTrue(file.isFile)
        val entries = detector.previews(file.readText(), "LA CIUDAD Y EL LABERINTO")
        assertEquals(25, entries.size)
        assertEquals(10, entries.count { it.level == 1 })
        assertEquals(15, entries.count { it.level == 2 })
        assertEquals("Nota al oyente. Dos mapas para un mismo viaje", entries.first().title)
        assertEquals("Guía de fuentes", entries.last().title)
    }
}
