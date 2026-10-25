# Reloj de Noche

Reloj de noche para Android, inspirado en el modo StandBy de iOS. Pensado para
mostrarse mientras el teléfono se carga (por ejemplo en un cargador
inalámbrico de velador).

## Qué hace

- Se muestra solo al cargar, como **protector de pantalla** (Ajustes >
  Pantalla > Protector de pantalla > "Reloj de Noche"), sobre la pantalla de
  bloqueo.
- También se puede abrir desde el ícono de la app, a pantalla completa.
- Tres estilos, se cambian deslizando hacia los lados:
  - **Digital**: dígitos grandes y redondeados.
  - **Analógico**: esfera con numerales y segundero de barrido continuo.
  - **Caída**: cada dígito que cambia cae desde arriba y rebota.
- **Modo noche**: con poca luz (sensor) todo se vuelve rojo tenue y baja el
  brillo. También se puede forzar a Siempre / Nunca.
- Fecha, próxima alarma, batería y estado de carga.
- Protección contra marcas en pantallas OLED (mueve el reloj cada minuto).

## Compilar

Proyecto estándar de Android (Kotlin + Jetpack Compose).

```
./gradlew assembleRelease
```

Requisitos: JDK 17 y el Android SDK (API 35). En local, Gradle toma la ruta del
SDK desde `local.properties` (`sdk.dir=...`), que no se versiona.

El APK se firma con la clave de *debug* si no hay un `keystore.properties`
propio. Para firmar con tu propia clave, crea `keystore.properties` en la raíz
con `storeFile`, `storePassword`, `keyAlias` y `keyPassword` (ese archivo está
en `.gitignore`).

## Créditos

- Tipografía [Nunito](https://github.com/googlefonts/nunito) (SIL Open Font
  License 1.1). Copia de la licencia en
  `app/src/main/assets/licenses/OFL-Nunito.txt`.
- Íconos basados en Material Icons (Apache License 2.0).
