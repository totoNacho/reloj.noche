# Estado del proyecto — Reloj de Noche

Documento para retomar el proyecto desde cualquier equipo (con o sin el
historial del chat de Kiro). Resume qué es, qué se hizo, qué falta y cómo
compilar/publicar. Mantener actualizado al cerrar cada tanda de cambios.

Última actualización: 2026-10-26.

## Qué es

App Android (Kotlin + Jetpack Compose, Material 3) que funciona como reloj de
velador, inspirada en el modo StandBy de iOS. Pensada para mostrarse mientras
el teléfono se carga (cargador inalámbrico). Teléfono objetivo del usuario:
Samsung Galaxy S26 Ultra.

- Paquete / applicationId: `cl.relojnoche`
- minSdk 26, targetSdk 35, compileSdk 35, JDK 17
- AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.09.03
- Repo privado: https://github.com/totoNacho/reloj.noche (rama `main`)

## Cómo se muestra al cargar

Dos caminos, el reloj es el mismo (`ClockScreen`):

1. **Protector de pantalla (DreamService)** — `ClockDreamService`. Android lo
   lanza al cargar cuando la pantalla iba a apagarse. Se activa en Ajustes del
   teléfono > Pantalla > Protector de pantalla > "Reloj de Noche".
2. **Activity a pantalla completa** — `ClockActivity`, lo que abre el ícono y
   lo que conviene lanzar desde **Modos y rutinas** de Samsung
   (Si: carga inalámbrica -> Abrir app Reloj de Noche). Se dibuja sobre la
   pantalla de bloqueo y mantiene la pantalla encendida mientras carga.

En Samsung, con **"Proteger batería"** (carga tope 80-85 %) activado, al llegar
al tope la carga se pausa y el sistema deja de considerarlo "cargando", así que
NO lanza el protector. Es límite de One UI, no de la app. Por eso la vía
recomendada al usuario es la rutina de Modos y rutinas, y el código tolera las
pausas de carga (ver abajo).

## Funciones implementadas

- **Tres estilos** (se cambian deslizando): Digital, Analógico, Caída (dígitos
  que caen y rebotan).
- **Color** elegible (paleta estilo iOS).
- **Modo noche**: Automático (sensor de luz), Siempre, Nunca. IMPORTANTE: el
  modo noche **conserva el color elegido** (NO tiñe de rojo); lo único que hace
  es bajar aún más el brillo.
- **Brillo**: brillo bajo SIEMPRE que el reloj está activo (no solo de noche),
  más un slider de brillo nocturno y un slider "Oscurecer más" (capa negra
  encima, para ir por debajo del mínimo del hardware).
- **Modo ahorro** (Ajustes > Modo ahorro): tras N minutos (por defecto 10) sin
  tocar la pantalla, el brillo baja al mínimo (`BRIGHTNESS_MIN`) y una capa negra
  al 50 % apaga el reloj con transición lenta; la pantalla NO se apaga. Un toque o
  deslizamiento reinicia la cuenta (`activityTick` en `ClockScreen`).
- **Sensor de luz espaciado**: `registerListener` con muestreo ~3 s y latencia
  máxima ~6 s (antes `SENSOR_DELAY_NORMAL`), para despertar menos el procesador.
- **Segundero suave** opcional (OFF por defecto): el barrido a 30 fps calienta,
  así que por defecto el reloj refresca 1 vez/seg (o 1 vez/min sin segundos).
- **Horario**: franja "desde/hasta" (soporta cruzar medianoche) + días de la
  semana; filtra cuándo aparece al cargar. IMPORTANTE: el horario SOLO aplica
  cuando el reloj lo abre la automatización (inicio automático / rutina, vía el
  extra `ClockActivity.EXTRA_AUTO`). Si abres la app desde el ícono, se muestra
  siempre, sin importar la hora. (Antes había un bug que la cerraba al abrirla
  a mano estando cargando; ya corregido.)
- **Inicio automático**: abre el reloj al conectar la carga sin rutina externa.
  Requiere el permiso "mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW). Honra
  franja horaria, umbral de batería y "solo inalámbrica". Lógica compartida en
  `AutoStart.kt` (`shouldAutoShow`, `launchClockAuto`, `canDrawOverlays`).
  - Vía principal: **`AutoStartService`** = servicio en PRIMER PLANO (estilo
    NightDream) que queda vivo y registra el receiver de carga en runtime, para
    que el evento no se pierda (en Samsung el receiver de manifiesto NO es
    fiable en segundo plano). Muestra una notificación permanente discreta
    (IMPORTANCE_MIN) con botón "Desactivar". START_STICKY.
  - `BootReceiver` revive el servicio tras BOOT_COMPLETED / MY_PACKAGE_REPLACED.
  - `RelojApp.onCreate` arranca el servicio si autoStart está activo.
  - `PowerConnectionReceiver` (manifiesto) queda solo como refuerzo.
  - Ajustes ofrece botón para excluir de optimización de batería
    (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) — clave en Samsung.
  - Permisos: SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
    FOREGROUND_SERVICE_SPECIAL_USE, RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS.
- **Protección OLED**: mueve el reloj unos dp cada minuto.
- Fecha, próxima alarma, batería y estado de carga.
- **Auto-actualización** (`UpdateChecker.kt`): en Ajustes > Actualizaciones,
  "Buscar actualización" consulta el último Release de GitHub
  (api.github.com/repos/totoNacho/reloj.noche/releases/latest), compara
  `tag_name` (sin la "v") contra `BuildConfig.VERSION_NAME`, y si hay una más
  nueva descarga el APK a cacheDir/updates y lanza el instalador del sistema vía
  FileProvider (authority `${applicationId}.fileprovider`, res/xml/file_paths.xml).
  Permiso REQUEST_INSTALL_PACKAGES. Versionado consistente: app = "1.0.<run>",
  tag = "v1.0.<run>" (github.run_number).
- Permisos declarados: SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(_SPECIAL_USE),
  RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS, INTERNET, ACCESS_NETWORK_STATE,
  REQUEST_INSTALL_PACKAGES. (Antes no tenía ninguno; los agregaron el inicio
  automático en segundo plano y la auto-actualización.)

## Mapa de archivos (app/src/main/java/cl/relojnoche/)

- `ClockSettings.kt` — `ClockSettings` (datos), `SettingsStore` (persistencia
  en SharedPreferences + StateFlow), paleta de colores, `faceColors()`,
  `effectiveBrightness()`, lógica de horario `isWithinSchedule()`.
- `ClockScreen.kt` — pantalla compartida (pager de estilos, overlay de
  controles, aplica brillo y capa de atenuación).
- `ClockFaces.kt` — `DigitalClock`, `FallingClock`, filas de fecha/info.
- `AnalogClock.kt` — esfera analógica (marcas, numerales, agujas).
- `ClockDigits.kt` — dígitos con animación (roll estilo iOS y caída).
- `ClockSources.kt` — hora (`rememberTimeMillis` con cadencia smooth/tick/min),
  batería (`BatteryInfo.isOnCharger` tolera pausas de carga), sensor de luz,
  próxima alarma, burn-in.
- `ClockActivity.kt` — Activity pantalla completa; sobre lock screen; mantiene
  pantalla encendida en el cargador; cierra al retirar con margen de gracia
  (20 s) por las pausas de carga; aplica horario si la abrió una rutina.
- `ClockDreamService.kt` — protector de pantalla; aplica onlyWireless + horario.
- `SettingsActivity.kt` — todos los ajustes + guía para activarlo.
- `Fonts.kt` — tipografía Nunito y medición de "tinta" de los dígitos.
- `RelojApp.kt` — Application, inicializa `SettingsStore`.

## Compilar

Requisitos: JDK 17 + Android SDK (API 35). Crear `local.properties` con
`sdk.dir=<ruta del SDK>` (no se versiona, es por máquina).

```
./gradlew assembleRelease
```

Sin keystore propio, el release se firma con la clave de debug. Para firmar con
clave propia: `keystore.properties` en la raíz (storeFile/storePassword/
keyAlias/keyPassword) — está en .gitignore.

## Compilación automática y firma (GitHub Actions)

`.github/workflows/build-apk.yml` se dispara en cada push a `main`:

- Siempre compila el APK de **debug** (artifact de la ejecución).
- Si están los **secrets** del repo, compila el **release firmado** y publica
  un Release en https://github.com/totoNacho/reloj.noche/releases

Secrets ya configurados en el repo: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` (= `relojnoche`), `KEY_PASSWORD`.

IMPORTANTE sobre la firma / keystore:
- El keystore es la identidad de la app. Su contraseña vive SOLO en los secrets
  de GitHub y en el gestor de contraseñas del usuario. NO está en el repo ni
  debe quedar en ningún commit.
- El primer APK firmado NO se instala encima del APK de debug que había antes
  (firmas distintas): hay que desinstalar una vez. Luego, todas las
  actualizaciones firmadas se instalan encima sin desinstalar.

## Retomar desde otro equipo

1. `git clone https://github.com/totoNacho/reloj.noche.git`
2. Abrir la carpeta en Kiro.
3. Crear `local.properties` con `sdk.dir=...` si vas a compilar local (o usar
   GitHub Actions y no instalar nada).
4. El historial del chat de Kiro puede no viajar entre equipos; este archivo y
   el repo son la fuente de verdad del estado del proyecto.

## Pendiente / próximos pasos

- Probar en el teléfono (S26 Ultra) la rutina de Modos y rutinas con el APK
  firmado nuevo, y confirmar que ya NO calienta y que el brillo queda cómodo.
- Posible ajuste: ¿el segundero debería tomar el color elegido en vez de
  naranja/rojo? (quedó pendiente de decidir con el usuario).
- Opcional: ícono/branding propio si se quiere pulir la presentación.
