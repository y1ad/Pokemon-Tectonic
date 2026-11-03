# Pokémon Tectonic – Port Android (APK)

Este directorio contiene todo lo necesario para compilar un APK autónomo de Tectonic con GitHub Actions.

## Cómo funciona

El workflow `.github/workflows/android.yml` tiene 3 jobs:

1. **game-data**: compila los PBS/plugins (genera `Data/*.dat` y `PluginScripts.rxdata`, que están en `.gitignore`) ejecutando el binario Linux del repo en modo headless (Xvfb) y empaqueta el juego en `game.zip`.
2. **engine**: clona `y1ad/mkxp-z-android-reworked`, le aplica el overlay de `android/overlay` (parches de rendimiento -O2/release, solo arm64-v8a, launcher, manifest) y compila el motor nativo + APK sin juego.
3. **package**: inyecta `game.zip` en `assets/`, hace `zipalign`, firma y publica el APK (artifact + Release).

En el primer arranque `LauncherActivity` extrae el juego a almacenamiento interno de la app (no pide permisos de archivos). Los arranques siguientes son instantáneos. Si no existen los datos compilados, la app los compila en el dispositivo (modo `debug compile`) como plan B.

## Compilar

Actions → **Android APK** → *Run workflow*. El APK aparece en *Releases* (tag `android-v<versión>`) y como artifact.

## Firma (importante para poder actualizar sin desinstalar)

Sin secrets, cada build se firma con una clave nueva y desechable → no se puede actualizar encima de una instalación anterior. Para una firma estable crea una vez un keystore:

```
keytool -genkeypair -v -keystore tectonic.jks -alias tectonic -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 tectonic.jks   # pegar en el secret KEYSTORE_BASE64
```

Secrets del repo: `KEYSTORE_BASE64`, `KEY_ALIAS`, `STORE_PASSWORD`, `KEY_PASSWORD` (opcional).

## Requisitos del dispositivo

Android 6.0+ (arm64), ~4 GB libres (APK + juego extraído).
