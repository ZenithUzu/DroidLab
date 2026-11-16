# DroidLab

**Laboratorio de ROMs Android en tu teléfono**

DroidLab es un proyecto gratuito para organizar y probar ROMs Android como sistemas invitados. La biblioteca de DroidLab 0.2 guarda varias GSI en el teléfono con perfiles separados. Funciona localmente, sin cuenta, suscripción ni Termux para gestionar archivos.

## Importar y administrar ROMs

1. Abre **Mis ROMs** y toca **+ Importar**.
2. Elige una o varias imágenes desde Archivos. Se admiten `.img`, `.gsi`, `.img.xz` y ZIP con una sola imagen de sistema reconocible, por ejemplo `system.img` o `system_gapps.img`.
3. Toca una tarjeta para ver el archivo de origen, formato y tamaño. Usa **⋮** para renombrar o eliminar esa ROM.

Cada importación guarda una copia de la imagen en el espacio privado de DroidLab y reserva un directorio independiente para los datos del futuro invitado. Puedes importar dos variantes de Miku UI y darles nombres distintos. Eliminar una tarjeta borra su copia y sus datos de perfil. El archivo de origen permanece en Archivos.

DroidLab reconoce cabeceras ext4, EROFS y Android sparse. La importación comprueba el formato, y la compatibilidad de arranque se verificará con la base virtual. Los ZIP de actualización de un dispositivo, por ejemplo los que contienen `payload.bin`, se rechazan. Durante la importación de un ZIP se necesita espacio para guardar temporalmente el archivo comprimido y la imagen extraída.

## Estado actual

| Función | Estado |
| --- | --- |
| Biblioteca local de varias GSI | Importar, ver detalles, renombrar y eliminar desde el APK |
| Arrancar un invitado ARM64 desde ISO o disco RAW | Motor experimental separado, accesible en **⋮ → Motor experimental (Termux)** |
| Arrancar AOSP 16 y Miku UI Blooming A3 | Pendiente de integrar la base Android y verificar el arranque en el POCO F7 Pro |
| QEMU integrado y pantalla del invitado en DroidLab | Pendiente |

## Compilar el APK

Con **JDK 17**, **Gradle 8.9** y el SDK de Android instalado:

```sh
sdkmanager 'platforms;android-35' 'build-tools;34.0.0'
gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. También se puede descargar como artefacto **DroidLab-debug** en una ejecución exitosa de [GitHub Actions](https://github.com/ZenithUzu/DroidLab/actions/workflows/android.yml).

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

## Objetivo Android y Miku UI

La primera candidata es **Miku UI Blooming A3**, una GSI oficial ARM64 basada en Android 16. El siguiente hito es arrancar **AOSP 16** con un kernel, un sistema de arranque y un `vendor` adaptados al mismo dispositivo virtual de QEMU. Luego podremos cambiar la imagen de sistema por la GSI de Miku UI y validar que llegue a inicio, responda al tacto y conserve datos tras reiniciar.

Una GSI contiene la imagen de sistema. El perfil virtual debe proveer el resto de las particiones y componentes de arranque, así que copiar `system.img` como `disk.raw` no es suficiente.

[GSI Blooming A3 (elige system.img o system_gapps.img)](https://sourceforge.net/projects/divarelease/files/gsi_phone_arm64_Blooming_A3/) · [Canal oficial de Miku UI](https://t.me/s/mikuuirelease) · [Documentación de GSIs](https://developer.android.com/topic/generic-system-image)
