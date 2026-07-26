# MD2Kindle — estado del proyecto

## Objetivo v3

Conservar la conversión offline de Markdown o texto a EPUB 3 de la v2 y conseguir
que Android ofrezca MD2Kindle de forma fiable en «Abrir con» para `.md`,
`.markdown` y `.txt`, incluidos documentos procedentes de gestores de archivos,
Telegram, Drive y otros proveedores.

## Criterios de cierre

- Código fuente completo y reproducible.
- Sin permiso de Internet ni acceso general al almacenamiento.
- Filtros explícitos `ACTION_VIEW` para URI `content://` con los MIME Markdown,
  texto y octet-stream usados por proveedores Android.
- Compatibilidad limitada por extensión con URI `file://` heredadas.
- Compatibilidad por extensión con URI que no declaran ningún MIME.
- Recepción de URI tanto en el arranque como mediante `onNewIntent`.
- Ignorar acciones, esquemas y documentos binarios no compatibles.
- Mantener `ACTION_SEND`, la selección manual y todas las funciones EPUB de v2.
- Pruebas unitarias para las reglas puras de admisión de documentos.
- Vista previa del índice antes de convertir.
- Índice EPUB 3 jerárquico, NCX y página de contenidos visible.
- Enlaces internos resueltos hacia destinos existentes.
- Prueba de regresión con `LA CIUDAD Y EL LABERINTO`.
- Pruebas unitarias, lint, EPUBCheck, firma y alineación del APK.
- APK release v3 compilada; el empaquetado final en la raíz se realiza después de
  la revisión.

## Estado

- Alcance: fijado.
- Implementación: completada.
- Verificación automatizada: aprobada.

## Verificación v3

- Compilación final `release`: `BUILD SUCCESSFUL`.
- Pruebas unitarias: 20/20 (incluidas 5 de admisión de intents/documentos).
- Android Lint: 0 errores y 10 avisos informativos sobre versiones disponibles y
  configuración de copia de seguridad, sin afectar a la compilación.
- APK: paquete `com.ricardo.md2kindle`, versionCode `3`, versionName `v3`.
- minSdk 24, targetSdk 34.
- Manifest compilado verificado con filtros `ACTION_VIEW` para `content://` y
  `file://`, por MIME y por extensión, incluidos proveedores sin MIME.
- Sin permiso de Internet ni permisos de almacenamiento general.
- APK release alineada y firmada con APK Signature Scheme v2.
- SHA-256: `7cf14b144529745e6772f2da1b187460426105788283a783087a9a3ebc2df3f2`.

La comprobación física del selector «Abrir con» requiere un dispositivo Android o
emulador con los proveedores concretos instalados.
