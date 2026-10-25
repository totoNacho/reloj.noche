// Build script raiz. Mismas versiones que Gear360App (ya probadas en esta maquina).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Desde Kotlin 2.0, Compose necesita su propio plugin de compilador.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
