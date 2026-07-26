# MD2Kindle — estado del proyecto

## Objetivo v2

Convertir en Android, completamente offline, archivos Markdown o texto a EPUB 3 compatible con «Enviar a Kindle», detectando una tabla de contenidos fiable aunque el documento combine H1, H2, H3 o rótulos de capítulo sin `#`.

## Criterios de cierre

- Código fuente completo y reproducible.
- Sin permiso de Internet ni acceso general al almacenamiento.
- Vista previa del índice antes de convertir.
- Índice EPUB 3 jerárquico, NCX y página de contenidos visible.
- Enlaces internos resueltos hacia destinos existentes.
- Prueba de regresión con `LA CIUDAD Y EL LABERINTO`.
- Pruebas unitarias, lint, EPUBCheck, firma y alineación del APK.
- APK v2 copiada en la raíz del proyecto.

## Estado

- Alcance: fijado.
- Implementación: completada.
- Verificación: aprobada.

## Verificación v2

- Compilación final `release`: `BUILD SUCCESSFUL`.
- Pruebas unitarias: 15/15.
- Documento real: 25 entradas, con 10 secciones principales y 15 capítulos subordinados.
- Android Lint: 0 errores.
- EPUBCheck 5.3.0 sobre el documento real y el libro de control: 0 errores y 0 avisos en ambos.
- Índice visible, navegación EPUB y NCX contienen la misma jerarquía y destinos.
- APK: paquete `com.ricardo.md2kindle`, versionCode `2`, versionName `v2`.
- minSdk 24, targetSdk 34.
- APK alineada y firmada con APK Signature Scheme v2.
- Sin permiso de Internet ni permisos de almacenamiento general.
- SHA-256: `14cc6923fe3ce526208240632362ae813c172be680961b8c0364acb2fc93a7f8`.

No había dispositivo Android ni emulador conectado para ejecutar una prueba física de instalación y del selector de Kindle.
