# Auditoría funcional — MD2Kindle v1

## Entrada

- Selector Android para `.md`, `.markdown` y `.txt`.
- Recepción mediante `Abrir con` y `Compartir`.
- Lectura por `ContentResolver`; no depende de rutas físicas.
- Límite de 25 MB para el texto, 15 MB por imagen y 60 MB para el conjunto de imágenes.

## Conversión

- CommonMark con tablas GFM.
- Encabezados H1 convertidos en capítulos.
- EPUB 3 con metadatos, navegación, NCX, estilos conservadores y portada opcional.
- Escape de HTML sin ejecutar contenido activo.
- Imágenes JPEG, PNG y GIF validadas por firma y emparejadas por nombre.
- Resolución desde el árbol CommonMark: admite imágenes directas, por referencia y destinos con paréntesis.
- Las imágenes remotas o ausentes no abortan: se sustituyen por texto y se informa.
- Los enlaces locales a recursos no empaquetados se neutralizan y se informa para evitar EPUB rotos.

## Salida

- Guardado con `ACTION_CREATE_DOCUMENT`.
- Compartición con `ACTION_SEND`, MIME `application/epub+zip`.
- URI segura mediante `FileProvider`, sin `file://`.
- El archivo temporal se sustituye en cada generación.

## Privacidad

- Conversión local.
- Sin permiso `INTERNET`.
- Sin `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` ni `MANAGE_EXTERNAL_STORAGE`.

## Límites conocidos

- Android no concede acceso automático a imágenes vecinas del Markdown. La v1 permite seleccionarlas expresamente y las empareja por nombre.
- Amazon realiza su propia conversión después de «Enviar a Kindle». Whispersync actúa cuando el documento ya forma parte de la biblioteca personal.
- La validación de código, EPUB y APK está completada; falta prueba física porque no había móvil ni emulador conectado.
