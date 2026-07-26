# MD2Kindle

Aplicación Android offline para convertir Markdown (`.md`, `.markdown`) y texto (`.txt`) en un EPUB compatible con «Enviar a Kindle».

## Funciones de la v2

- Abrir un documento desde Android o recibirlo desde «Compartir».
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
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

El APK resultante se genera en `app/build/outputs/apk/debug/app-debug.apk`.

## Flujo Kindle

La app crea el EPUB y abre el menú Compartir. Después hay que elegir Kindle o «Enviar a Kindle» y completar la incorporación a la biblioteca de Amazon. Whispersync empieza a actuar una vez que Amazon ha añadido el documento personal a la biblioteca.
