# MD2Kindle

Aplicación Android offline para convertir Markdown (`.md`, `.markdown`) y texto (`.txt`) en un EPUB compatible con «Enviar a Kindle».

## Funciones de la v3

- Aparecer en «Abrir con» al tocar archivos `.md`, `.markdown` y `.txt` en gestores
  de archivos y proveedores como Telegram o Drive.
- Recibir documentos desde «Compartir».
- Admitir los MIME Markdown habituales, `text/plain` y los proveedores que entregan
  Markdown como `application/octet-stream`, además de URI que solo conservan la
  extensión y no declaran ningún MIME.
- Validar en tiempo de ejecución la acción, el esquema, el MIME y la extensión para
  no intentar procesar documentos binarios como texto.
- Procesar tanto el intent de arranque como nuevos intents recibidos por una actividad
  ya abierta.
- Detectar título desde el primer encabezado y permitir editar título, autor e idioma.
- Detectar capítulos desde H1, H2, H3 y rótulos como «Capítulo 1» aunque no usen `#`.
- Mostrar una vista previa jerárquica del índice antes de convertir.
- Incluir una página de índice visible y navegación interna compatible con «Ir a / Índice» de Kindle.
- Reescribir los enlaces internos del Markdown para que apunten al capítulo correcto del EPUB.
- Conservar encabezados, énfasis, listas, citas, código, enlaces y tablas Markdown.
- Añadir portada e imágenes locales seleccionadas.
- Generar EPUB 3 con navegación e índice NCX de compatibilidad.
- Guardar el EPUB mediante el selector del sistema.
- Compartir el EPUB con Kindle u otra aplicación.
- Conversión totalmente local, sin permiso de Internet.

## Compilar

```bash
./gradlew clean testDebugUnitTest lintDebug assembleRelease
```

El APK resultante se genera en `app/build/outputs/apk/release/app-release.apk`.

## Flujo Kindle

La app crea el EPUB y abre el menú Compartir. Después hay que elegir Kindle o «Enviar a Kindle» y completar la incorporación a la biblioteca de Amazon. Whispersync empieza a actuar una vez que Amazon ha añadido el documento personal a la biblioteca.
