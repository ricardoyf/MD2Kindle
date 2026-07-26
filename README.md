# MD2Kindle

Aplicación Android offline para convertir Markdown (`.md`, `.markdown`) y texto (`.txt`) en un EPUB compatible con «Enviar a Kindle».

## Funciones de la v1

- Abrir un documento desde Android o recibirlo desde «Compartir».
- Detectar título desde el primer encabezado y permitir editar título, autor e idioma.
- Crear capítulos a partir de encabezados de nivel 1.
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
