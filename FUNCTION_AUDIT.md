# Auditoría funcional — MD2Kindle v2

## Entrada

- Selector Android para `.md`, `.markdown` y `.txt`.
- Recepción mediante `Abrir con` y `Compartir`.
- Lectura por `ContentResolver`; no depende de rutas físicas.
- Límite de 25 MB para el texto, 15 MB por imagen y 60 MB para el conjunto de imágenes.
- Vista previa de las entradas y su jerarquía antes de crear el EPUB.

## Detección del índice

- Reconoce H1, H2 y H3 según la estructura del documento.
- Reconoce rótulos aislados como `Capítulo`, `Lección`, `Sección`, `Parte`, `Prólogo` y `Epílogo` aunque no lleven `#`.
- Separa el título y los metadatos iniciales de los capítulos navegables.
- Si existe un mapa manual, lo sustituye por un índice generado para evitar duplicados y enlaces rotos.
- Conserva dentro del texto los subtítulos H4 y los H3 no utilizados como capítulos.
- Genera identificadores ASCII estables, únicos y aptos para EPUB/Kindle.

## Conversión

- CommonMark con tablas GFM.
- EPUB 3 con página de índice visible, navegación jerárquica, NCX de compatibilidad y portada opcional.
- Los enlaces internos del Markdown se reescriben hacia el capítulo EPUB correspondiente.
- Escape de HTML sin ejecutar contenido activo.
- Imágenes JPEG, PNG y GIF validadas por firma y emparejadas por nombre.
- Imágenes directas, por referencia y destinos con paréntesis.
- Las imágenes remotas o ausentes no abortan: se sustituyen por texto y se informa.
- Los enlaces locales no empaquetados se neutralizan para evitar EPUB rotos.

## Salida y privacidad

- Guardado con `ACTION_CREATE_DOCUMENT`.
- Compartición con `ACTION_SEND`, MIME `application/epub+zip`.
- URI segura mediante `FileProvider`, sin `file://`.
- Conversión local, sin permiso `INTERNET` ni permisos generales de almacenamiento.

## Límite operativo

Amazon realiza su propia incorporación después de «Enviar a Kindle» y Whispersync actúa cuando el documento forma parte de la biblioteca personal. El EPUB y el APK están validados; no había móvil ni emulador conectado para una prueba física de la instalación y del flujo final de Amazon.
