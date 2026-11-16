# DroidLab

**Laboratorio de ROMs Android en tu teléfono**

DroidLab es un proyecto gratuito para organizar y probar ROMs Android con QEMU ARM64 integrado. Las máquinas y sus datos viven en el teléfono. El arranque virtual se hace desde la app.

## Arrancar una ROM

1. Abre **⋮ → Bases virtuales** e instala **LineageOS 23.0 · Android 16 ARM64**. La app descarga 1.00 GB y verifica su SHA-256 antes de extraer los discos.
2. Importa una GSI ARM64 compatible desde **+ Importar**. Abre su tarjeta y toca **Arrancar**. DroidLab prepara un disco de datos y conecta la GSI como el disco de sistema del invitado.
3. Usa la pantalla de la app para interactuar con el invitado. En el primer arranque, Android prepara aplicaciones y datos. Si el invitado se detiene, el botón **Registro** muestra la salida de QEMU y del kernel.

DroidLab extrae el disco de la base y crea una copia privada para cada perfil. Reserva espacio adicional para la GSI y los datos de Android. Con **Abrir LineageOS** puedes iniciar la base con un perfil propio.

[Base para QEMU de jqssun](https://github.com/jqssun/android-lineage-qemu/releases/tag/v2025.10.14) · [GSIs de Google](https://developer.android.com/topic/generic-system-image/releases)

## Importar y administrar ROMs

1. Abre **Mis ROMs** y toca **+ Importar**.
2. Elige una o varias imágenes desde Archivos. Se admiten `.img`, `.gsi`, `.img.xz` y ZIP con una sola imagen de sistema reconocible, por ejemplo `system.img` o `system_gapps.img`.
3. Toca una tarjeta para ver el archivo de origen, formato y tamaño. Usa **⋮** para renombrar o eliminar esa ROM.

Durante la importación, una tarjeta muestra el archivo en turno, la etapa y los bytes procesados. El porcentaje se refiere a la etapa actual y aparece cuando se conoce su tamaño. Al descomprimir XZ, se muestra cuánto se ha escrito hasta el momento.

Cada importación guarda una copia de la imagen en el espacio privado de DroidLab y reserva un directorio independiente para los datos del invitado. Puedes importar dos variantes de Miku UI y darles nombres distintos. Eliminar una tarjeta borra su copia y sus datos de perfil. El archivo de origen permanece en Archivos.

DroidLab reconoce cabeceras ext4, EROFS y Android sparse. La importación comprueba el formato. La imagen debe ser compatible con el kernel, las HAL y la política SELinux de la base virtual. Los ZIP de actualización de un dispositivo, por ejemplo los que contienen `payload.bin`, se rechazan. Durante la importación de un ZIP se necesita espacio para guardar temporalmente el archivo comprimido y la imagen extraída.

## Estado actual

| Función | Estado |
| --- | --- |
| Biblioteca local de varias GSI | Importar, ver detalles, renombrar y eliminar desde el APK |
| Arrancar un invitado ARM64 desde ISO o disco RAW | Motor experimental separado, accesible en **⋮ → Motor experimental (Termux)** |
| Miku UI Udon v2 (Android 14) | La base Android 16 monta el sistema, pero se detiene por incompatibilidad de SELinux |
| QEMU ARM64 integrado | Prueba experimental autónoma en **⋮ → Comprobar motor integrado** |
| Pantalla y entrada del invitado Android | Integradas en el APK mediante un socket privado de QEMU |

## Compilar el APK

Con **JDK 17**, **Gradle 8.9** y el SDK de Android instalado:

```sh
sdkmanager 'platforms;android-35' 'build-tools;34.0.0'
gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. También se puede descargar como artefacto **DroidLab-debug** en una ejecución exitosa de [GitHub Actions](https://github.com/ZenithUzu/DroidLab/actions/workflows/android.yml).

## Motor ARM64 integrado

El APK de GitHub Actions incluye QEMU ARM64 con TCG. Desde **⋮ → Comprobar motor integrado**,
DroidLab inicia QEMU y ejecuta un pequeño programa invitado que devuelve una señal por el puerto serial.
La prueba se ejecuta desde el APK sin Termux. La ejecución de Android y el arranque de las ROMs importadas
requieren una base invitada con sus particiones y controladores virtuales.

Para reproducir el APK con el motor, ejecuta en Linux con Python 3.10+, `readelf`, `dpkg-deb` y
`patchelf` 0.19.3 antes de compilar con Gradle:

```sh
python3 engine/native/package_qemu.py --out app/build/generated/nativeEngine
gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Las sumas SHA-256 de los paquetes y la revisión de sus recetas y parches están en
[termux-arm64.lock.json](engine/native/termux-arm64.lock.json). El APK incluye el manifiesto
`assets/qemu/sources.json` con los paquetes usados. [Código fuente y recetas de Termux](https://github.com/termux/termux-packages/tree/65ec9af258548453164acccf96e007411f1ea81d)

## Preparar Termux

Esta sección es para probar el motor experimental con un invitado compatible, como Alpine Linux. Puedes utilizar la biblioteca de GSI directamente desde el APK.

1. Instala una versión actual de [Termux](https://github.com/termux/termux-app#installation) y un visor VNC. Abre Termux para que complete su instalación.
2. Instala el motor y descarga el lanzador de DroidLab.

   ```sh
   pkg install python curl qemu-system-aarch64-headless
   mkdir -p ~/.droidlab/profiles/alpine
   curl -fL https://raw.githubusercontent.com/ZenithUzu/DroidLab/main/engine/droidlab.py \
     -o ~/.droidlab/droidlab.py
   ```

3. Obtén **QEMU_EFI.fd** para ARM64 desde una distribución confiable, por ejemplo el paquete [qemu-efi-aarch64 de Debian](https://packages.debian.org/trixie/qemu-efi-aarch64). Dentro del paquete se encuentra en `/usr/share/qemu-efi-aarch64/QEMU_EFI.fd`. Descárgalo o cópialo primero a Descargas y luego, desde Termux, otorga acceso a almacenamiento con `termux-setup-storage` y copia el firmware a `~/.droidlab/QEMU_EFI.fd`.
4. Edita `~/.termux/termux.properties` y deja una sola línea `allow-external-apps = true`. Ejecuta `termux-reload-settings`. Al tocar **Arrancar máquina**, concede a DroidLab el permiso para ejecutar comandos en Termux. En algunas versiones de Android está en Ajustes → Apps → DroidLab → Permisos adicionales. [Documentación de Termux](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

### Primer arranque

Usa la ISO **Virtual aarch64** de [Alpine Linux](https://alpinelinux.org/downloads/) como prueba de firmware, pantalla y entrada. Verifica su suma SHA-256 publicada y guarda la ISO como `~/.droidlab/profiles/alpine/boot.iso`. También puedes añadir un disco RAW al perfil como `disk.raw` si vas a instalar un sistema. Un disco RAW ya arrancable se puede usar por sí solo.

En **⋮ → Motor experimental (Termux)**, usa el perfil **alpine**, toca **Arrancar máquina** y conecta el visor a **127.0.0.1:5900**. La conexión VNC escucha en el propio teléfono, sin contraseña. Otras apps del mismo teléfono pueden acceder a ese puerto. El motor admite un invitado activo a la vez.

Los mensajes del motor se imprimen en Termux. Cada perfil guarda `serial.log` y `qemu.log` del último arranque. Para usar el motor directamente:

```sh
python ~/.droidlab/droidlab.py start alpine
# En otra sesión de Termux
python ~/.droidlab/droidlab.py status alpine
python ~/.droidlab/droidlab.py stop alpine
```

Usa la opción de apagado del propio invitado si este no responde a la solicitud. El comando `force-stop` termina QEMU de inmediato y puede perder escrituras que el invitado todavía no guardó.

## Compatibilidad de las ROMs

La base incluida corresponde a Android 16. **Miku UI Udon v2 0.12.0** es Android 14 y, en la prueba de arranque, carecía de `/system/etc/selinux/mapping/202504.cil`, requerido por el `vendor` de la base LineageOS de Android 16. Se necesita una base compatible con Android 14 para esa ROM. La revisión de Android también importa dentro de una misma versión, ya que las políticas de la base y del sistema pueden cambiar entre actualizaciones.

Los ZIP que contienen una GSI se abren como imágenes de sistema. Los ZIP de actualización con `payload.bin` y las ROMs específicas de un modelo requieren un método de instalación y una base virtual compatibles con ese paquete.

[Udon v2 en GitHub](https://github.com/xiaoleGun/treble_build_miku/releases/tag/Udon_v2-0.12.0) · [GSI Blooming A3](https://sourceforge.net/projects/divarelease/files/gsi_phone_arm64_Blooming_A3/) · [Documentación de GSIs](https://developer.android.com/topic/generic-system-image)
