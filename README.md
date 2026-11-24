# AdBlock para LDPlayer

Bloqueador de anuncios a nivel de DNS. No necesita root.

## Cómo funciona
La app crea una VPN local que **solo** captura las consultas DNS (la ruta de la VPN es una única IP virtual, 10.111.222.2; el resto del tráfico no entra en el túnel). Cuando una app pregunta por un dominio de la lista (por ejemplo `ads.applovin.com`), la app responde `0.0.0.0` y el anuncio no se descarga. Las demás consultas se reenvían a 1.1.1.1 (y 8.8.8.8 de respaldo) por un socket protegido, fuera del túnel.
Hay una lista mínima incluida; el botón *Actualizar lista de anuncios* descarga la lista pública StevenBlack (decenas de miles de dominios).

## Compilar el APK
Opción A, Android Studio: abre esta carpeta y usa *Build → Build APK(s)*.
Opción B, terminal (JDK 17 + Android SDK con plataforma 34): `./gradlew assembleDebug`.
Opción C, sin instalar nada: sube la carpeta a un repositorio de GitHub; el workflow *Build APK* deja los APK como artefacto.

Recomendado: `app/build/outputs/apk/debug/app-debug.apk`.

## Si la instalación falla
1. Desinstala AdBlock del emulador si ya estaba.
2. Usa `app-debug.apk`.
3. Para ver el error real: `adb devices` y `adb install -r ruta\app-debug.apk` desde la carpeta de LDPlayer.

## Usar
Abre **AdBlock**, pulsa *Iniciar bloqueo* y acepta el aviso de conexión VPN de Android. Aparece un icono de llave en la barra de estado. Pulsa *Actualizar lista de anuncios* una vez.

## Límites (importante)
- Bloquea por **dominio**. Los anuncios que se sirven desde el mismo dominio que el contenido (YouTube, anuncios dentro de la app de Facebook o Instagram) no se pueden quitar con DNS.
- Si en Ajustes tienes «DNS privado» en modo estricto, o una app usa su propio DNS cifrado (DoH), se salta el filtro.
- Android solo permite una VPN activa a la vez: no funcionará junto con otra VPN.
- Algunas apps y juegos dejan de funcionar si no logran cargar un anuncio. Añade su dominio a *Excepciones*.
- Bloquear anuncios puede ir contra las condiciones de uso de algunos juegos o servicios.
- La lista se carga al iniciar el bloqueo; al actualizarla con el bloqueo activo se aplica de inmediato.
