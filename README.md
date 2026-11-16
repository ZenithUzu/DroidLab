# DroidLab

**Laboratorio de ROMs Android en tu teléfono**

DroidLab es un proyecto para ejecutar sistemas invitados ARM64 dentro de Android. La primera versión conecta el APK con QEMU en Termux y usa un visor VNC para mostrar la pantalla del invitado. Cada perfil guarda sus imágenes virtuales y registros en un directorio de Termux.

## Estado actual

| Función | Estado |
| --- | --- |
| Arrancar un invitado ARM64 desde UEFI, ISO o disco RAW | Motor inicial con QEMU en Termux |
| Consultar estado y solicitar apagado | Implementado mediante QMP |
| Pantalla, teclado y apuntador virtuales | Visor VNC externo, por conexión local |
| Compilar el APK | GitHub Actions genera un APK de depuración |
| Arrancar AOSP 16 y Miku UI Blooming A3 | Pendiente de adaptar y verificar el hardware virtual Android |

La app ejecuta QEMU por software, con 4 núcleos virtuales y 3 GiB de RAM para el invitado. La VM funciona sin red invitada. El POCO F7 Pro es el primer teléfono objetivo y puede conservar HyperOS y su bootloader bloqueado.

## Compilar el APK

Con **JDK 17**, **Gradle 8.9** y el SDK de Android instalado:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
gradle :app:assembleDebug :app:lintDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. También se puede descargar como artefacto **DroidLab-debug** en una ejecución exitosa de [GitHub Actions](https://github.com/ZenithUzu/DroidLab/actions/workflows/android.yml).

## Preparar Termux

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

En DroidLab, usa el perfil **alpine**, toca **Arrancar máquina** y conecta el visor a **127.0.0.1:5900**. La conexión VNC escucha en el propio teléfono, sin contraseña. Otras apps del mismo teléfono pueden acceder a ese puerto. El motor admite un invitado activo a la vez.

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

[Canal oficial de Miku UI](https://t.me/s/mikuuirelease) · [Documentación de GSIs](https://developer.android.com/topic/generic-system-image)
