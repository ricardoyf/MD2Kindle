package com.ricardo.md2kindle.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubGeneratorTest {
    private val generator = EpubGenerator()
    private val metadata = EpubMetadata(
        title = "Prueba & lectura",
        author = "Ricardo <Autor>",
        language = "es",
        identifier = "urn:uuid:12345678-1234-1234-1234-123456789abc",
        modifiedEpochMillis = 1_753_545_600_000L,
    )

    @Test
    fun `crea epub con mimetype primero y sin comprimir`() = withGeneratedEpub(
        EpubBook(
            markdown = "# Capítulo uno\n\nTexto con **negrita**.",
            metadata = metadata,
        ),
    ) { file, result ->
        assertEquals(1, result.chapterCount)
        ZipInputStream(file.inputStream()).use { zip ->
            val first = zip.nextEntry
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals(EpubGenerator.EPUB_MIMETYPE, zip.readBytes().toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun `incluye estructura epub3 navegacion capitulos y metadatos escapados`() =
        withGeneratedEpub(
            EpubBook(
                markdown = """
                    Introducción previa.

                    # Primero

                    Texto con *cursiva*, **negrita**, `código & <seguro>` y ñ.

                    # Segundo

                    | A | B |
                    |---|---|
                    | 1 | 2 |
                """.trimIndent(),
                metadata = metadata,
            ),
        ) { file, result ->
            assertEquals(2, result.chapterCount)
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue("META-INF/container.xml" in names)
                assertTrue("EPUB/package.opf" in names)
                assertTrue("EPUB/nav.xhtml" in names)
                assertTrue("EPUB/toc.ncx" in names)
                assertTrue("EPUB/contents.xhtml" in names)
                assertTrue(names.toString(), "EPUB/frontmatter.xhtml" in names)
                assertTrue("EPUB/chapter_1.xhtml" in names)
                assertTrue("EPUB/chapter_2.xhtml" in names)

                val opf = zip.readText("EPUB/package.opf")
                assertTrue(opf.contains("Prueba &amp; lectura"))
                assertTrue(opf.contains("Ricardo &lt;Autor&gt;"))

                val second = zip.readText("EPUB/chapter_2.xhtml")
                assertTrue(second.contains("<table>"))
                assertTrue(second.contains("<td>1</td>"))
            }
        }

    @Test
    fun `incrusta imagen local y avisa por imagen ausente y remota`() =
        withGeneratedEpub(
            EpubBook(
                markdown = """
                    # Imágenes

                    ![Foto local](fóto.jpg)
                    ![Falta](ausente.png)
                    ![Remota](https://example.com/remota.png)
                """.trimIndent(),
                metadata = metadata,
                images = listOf(
                    EpubAsset(
                        sourceName = "foto.jpg",
                        mediaType = "image/jpeg",
                        bytes = byteArrayOf(1, 2, 3, 4),
                    ),
                ),
            ),
        ) { file, result ->
            assertEquals(1, result.embeddedImageCount)
            assertTrue(result.warnings.any { "ausente.png" in it })
            assertTrue(result.warnings.any { "https://example.com" in it })
            ZipFile(file).use { zip ->
                assertTrue(zip.getEntry("EPUB/images/image_1.jpg") != null)
                val chapter = zip.readText("EPUB/chapter_1.xhtml")
                assertTrue(chapter.contains("""src="images/image_1.jpg""""))
                assertTrue(chapter.contains("imagen no encontrada"))
                assertFalse(chapter.contains("""src="https://example.com"""))
            }
        }

    @Test
    fun `resuelve imagenes por referencia y destinos con parentesis`() =
        withGeneratedEpub(
            EpubBook(
                markdown = """
                    # Formas válidas

                    ![Por referencia][imagen-principal]
                    ![Con paréntesis](foto(1).png)

                    [imagen-principal]: foto-referencia.png
                """.trimIndent(),
                metadata = metadata,
                images = listOf(
                    EpubAsset("foto-referencia.png", "image/png", byteArrayOf(1, 2, 3)),
                    EpubAsset("foto(1).png", "image/png", byteArrayOf(4, 5, 6)),
                ),
            ),
        ) { file, result ->
            assertEquals(2, result.embeddedImageCount)
            assertTrue(result.warnings.isEmpty())
            ZipFile(file).use { zip ->
                assertTrue(zip.getEntry("EPUB/images/image_1.png") != null)
                assertTrue(zip.getEntry("EPUB/images/image_2.png") != null)
                val chapter = zip.readText("EPUB/chapter_1.xhtml")
                assertTrue(chapter.contains("""src="images/image_1.png""""))
                assertTrue(chapter.contains("""src="images/image_2.png""""))
            }
        }

    @Test
    fun `neutraliza enlaces locales que no forman parte del epub`() =
        withGeneratedEpub(
            EpubBook(
                markdown = """
                    # Enlaces

                    [Capítulo externo](otro-capitulo.md)
                    [Web](https://example.com)
                """.trimIndent(),
                metadata = metadata,
            ),
        ) { file, result ->
            assertTrue(result.warnings.any { "otro-capitulo.md" in it })
            ZipFile(file).use { zip ->
                val chapter = zip.readText("EPUB/chapter_1.xhtml")
                assertFalse(chapter.contains("""href="otro-capitulo.md""""))
                assertTrue(chapter.contains("""href="https://example.com""""))
                assertTrue(chapter.contains("Capítulo externo"))
            }
        }

    @Test
    fun `incluye portada en manifest y spine`() = withGeneratedEpub(
        EpubBook(
            markdown = "# Libro\n\nContenido.",
            metadata = metadata,
            cover = EpubAsset(
                sourceName = "portada.png",
                mediaType = "image/png",
                bytes = byteArrayOf(9, 8, 7),
            ),
        ),
    ) { file, result ->
        assertEquals(1, result.embeddedImageCount)
        ZipFile(file).use { zip ->
            assertTrue(zip.getEntry("EPUB/images/cover.png") != null)
            assertTrue(zip.getEntry("EPUB/cover.xhtml") != null)
            val opf = zip.readText("EPUB/package.opf")
            assertTrue(opf.contains("""properties="cover-image""""))
            assertTrue(opf.contains("""idref="cover-page" linear="no""""))
        }
    }

    @Test
    fun `genera indice visible y navegacion jerarquica con enlaces internos validos`() =
        withGeneratedEpub(
            EpubBook(
                markdown = """
                    # Libro

                    ## Mapa de escucha

                    - [Capítulo](#1-capítulo-ámbito)

                    ## Primera parte

                    Véase [el capítulo](#1-capítulo-ámbito).

                    ### 1. Capítulo ámbito

                    Texto.

                    ## Epílogo
                """.trimIndent(),
                metadata = metadata,
            ),
        ) { file, result ->
            assertEquals(3, result.chapterCount)
            ZipFile(file).use { zip ->
                val contents = zip.readText("EPUB/contents.xhtml")
                val nav = zip.readText("EPUB/nav.xhtml")
                val ncx = zip.readText("EPUB/toc.ncx")
                val first = zip.readText("EPUB/chapter_1.xhtml")
                val second = zip.readText("EPUB/chapter_2.xhtml")
                assertTrue(contents.contains("""href="chapter_2.xhtml#1-capitulo-ambito""""))
                assertTrue(nav.contains("<ol>"))
                assertTrue(ncx.contains("""content="2""""))
                assertTrue(first.contains("""href="chapter_2.xhtml#1-capitulo-ambito""""))
                assertTrue(second.contains("""id="1-capitulo-ambito""""))
                assertFalse(contents.contains("Mapa de escucha"))
            }
        }

    @Test
    fun `todos los xml y xhtml generados son parseables`() = withGeneratedEpub(
        EpubBook(
            markdown = """
                # Inicio & símbolos

                > Cita con acentos: información.

                1. Uno
                2. Dos

                ```xml
                <dato valor="A&B"/>
                ```
            """.trimIndent(),
            metadata = metadata,
        ),
    ) { file, _ ->
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter {
                    it.name.endsWith(".xml") ||
                        it.name.endsWith(".xhtml") ||
                        it.name.endsWith(".opf") ||
                        it.name.endsWith(".ncx")
                }
                .forEach { entry ->
                    zip.getInputStream(entry).use { input ->
                        factory.newDocumentBuilder().parse(input)
                    }
                }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rechaza markdown vacio`() {
        val file = File.createTempFile("md2kindle-empty", ".epub")
        file.deleteOnExit()
        FileOutputStream(file).use {
            generator.write(EpubBook("  ", metadata), it)
        }
    }

    @Test
    fun `genera muestra representativa para epubcheck`() {
        val validationDir = File("build/validation").apply { mkdirs() }
        val target = File(validationDir, "MD2Kindle-smoke.epub")
        val tinyPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        FileOutputStream(target).use { output ->
            val result = generator.write(
                EpubBook(
                    markdown = """
                        Este libro de prueba contiene texto introductorio.

                        # Primer capítulo

                        Texto con **negrita**, *cursiva*, [enlace](https://www.amazon.es/sendtokindle) y caracteres españoles: áéíóú, ü y ñ.

                        > Una cita breve.

                        ![Ilustración](ilustracion.png)
                        ![Imagen por referencia][ref-imagen]
                        ![Imagen con paréntesis](figura(1).png)

                        [ref-imagen]: referencia.png

                        # Segundo capítulo

                        1. Primer punto.
                        2. Segundo punto.

                        | Campo | Valor |
                        |---|---|
                        | Idioma | Español |
                    """.trimIndent(),
                    metadata = metadata.copy(title = "Libro de validación MD2Kindle"),
                    images = listOf(
                        EpubAsset("ilustración.png", "image/png", tinyPng),
                        EpubAsset("referencia.png", "image/png", tinyPng),
                        EpubAsset("figura(1).png", "image/png", tinyPng),
                    ),
                    cover = EpubAsset("portada.png", "image/png", tinyPng),
                ),
                output,
            )
            assertEquals(2, result.chapterCount)
            assertEquals(4, result.embeddedImageCount)
        }
        assertTrue(target.isFile)
        assertTrue(target.length() > 1_000)
    }

    @Test
    fun `genera epub del documento real cuando se facilita por entorno`() {
        val path = System.getenv("MD2KINDLE_REAL_MD") ?: return
        val source = File(path)
        assertTrue(source.isFile)
        val validationDir = File("build/validation").apply { mkdirs() }
        val target = File(validationDir, "LA_CIUDAD_Y_EL_LABERINTO-v2.epub")

        FileOutputStream(target).use { output ->
            val result = generator.write(
                EpubBook(
                    markdown = source.readText(),
                    metadata = metadata.copy(title = "LA CIUDAD Y EL LABERINTO"),
                ),
                output,
            )
            assertEquals(25, result.chapterCount)
            assertTrue(result.warnings.isEmpty())
        }
        assertTrue(target.length() > 10_000)
    }

    private fun withGeneratedEpub(
        book: EpubBook,
        assertions: (File, EpubResult) -> Unit,
    ) {
        val file = File.createTempFile("md2kindle-test", ".epub")
        file.deleteOnExit()
        val result = FileOutputStream(file).use { generator.write(book, it) }
        assertions(file, result)
    }

    private fun ZipFile.readText(path: String): String =
        getInputStream(getEntry(path)).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
