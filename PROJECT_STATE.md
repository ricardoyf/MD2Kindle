# MD2Kindle — estado del proyecto

## Objetivo v1

Convertir en Android, completamente offline, archivos Markdown o texto a EPUB 3 compatible con «Enviar a Kindle». La app debe guardar el EPUB y compartirlo mediante una URI segura.

## Criterios de cierre

- Código fuente completo y reproducible.
- Sin permiso de Internet ni acceso general al almacenamiento.
- Entrada por selector Android, `Abrir con` y `Compartir`.
- Título, autor, idioma, portada opcional e imágenes locales seleccionadas.
- EPUB 3 con índice, capítulos, estilos conservadores y NCX de compatibilidad.
- `mimetype` como primera entrada ZIP, sin compresión.
- Pruebas unitarias, lint, validación EPUBCheck, firma y alineación del APK.
- APK v1 copiada en la raíz del proyecto.

## Estado

- Alcance: fijado.
- Implementación: completada.
- Verificación: aprobada.

## Verificación v1

- Compilación limpia: `BUILD SUCCESSFUL`.
- Pruebas unitarias: 9/9.
- Android Lint: 0 errores.
- EPUBCheck 5.3.0 sobre libro representativo: 0 errores y 0 avisos.
- La muestra incluye imágenes directas, por referencia y con paréntesis en el nombre.
- ZIP EPUB: estructura íntegra, `mimetype` primero y sin compresión.
- APK: paquete `com.ricardo.md2kindle`, versionCode `1`, versionName `v1`.
- minSdk 24, targetSdk 34.
- APK alineada y firmada con APK Signature Scheme v2.
- Sin permiso de Internet ni permisos de almacenamiento general.
- Compilación final `release`, sin modo depuración ni componentes de tooling.
- SHA-256: `69be337904537858858afec49b373b1c9266b838bf3f8bc68d4de6c2c48a1601`.

No había dispositivo Android ni emulador conectado para ejecutar una prueba física de instalación y del selector de Kindle.
