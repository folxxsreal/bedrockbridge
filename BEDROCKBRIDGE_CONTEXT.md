# Proyecto: BedrockBridge — Mod de Minecraft Fabric

> **Cómo usar este documento**: pégalo al inicio de una sesión de Claude Code dentro de `~/bedrockbridge`, y Claude Code retomará exactamente donde quedamos.

---

## ¿Qué es?

Un mod cliente para Minecraft Java 26.1.2 que permite a jugadores de Minecraft Bedrock conectarse a un mundo single-player con **un solo botón**, incluso desde redes WiFi distintas (sin tener que abrir puertos del router, sin saber IP pública, sin VPN).

Combina tres cosas que ya existen por separado pero nadie ha integrado:

1. **Geyser** — proxy que traduce protocolo Bedrock ↔ Java
2. **Floodgate** — permite a Bedrock entrar sin cuenta Java premium
3. **Playit.gg** — túnel público que evita NAT/CGNAT, soporta UDP

---

## Mi sistema

- **OS**: Fedora 44 Linux
- **Desktop**: KDE Plasma 6 sobre Wayland
- **Usuario**: dnixxv
- **Home**: `/home/dnixxv`
- **CPU**: AMD Ryzen 7 5700U
- **GPU**: Lucienne (integrada AMD)
- **Idioma**: hablo español (bilingüe con inglés), prefiero respuestas en español

---

## Software instalado

- **Java**: `javac 25.0.3` (system, en `/usr/lib/jvm/`) + JBR 25.0.2 (bundled con IntelliJ Flatpak)
- **Git**: instalado
- **Node.js**: v24.15.0 vía nvm
- **Prism Launcher**: instalado vía Flatpak (`org.prismlauncher.PrismLauncher`)
- **IntelliJ IDEA Community**: instalado vía Flatpak (`com.jetbrains.IntelliJ-IDEA-Community`)
- **flatpak**: configurado con flathub

---

## Problema conocido: IntelliJ Flatpak no puede lanzar Minecraft

Cuando IntelliJ corre dentro del sandbox de Flatpak, no le pasa correctamente acceso a Wayland/X11 al proceso hijo (Minecraft). Crash con:

```
[GLFW 0x1000E] X11: The DISPLAY environment variable is missing
```

**Workaround actual**: editar código en IntelliJ, pero lanzar Minecraft con `./gradlew runClient` desde una terminal nativa (no la de IntelliJ).

**Posibles fixes futuros (NO aplicar todavía)**:
- Reinstalar IntelliJ desde tarball oficial en lugar de Flatpak
- Configurar permisos del Flatpak con Flatseal
- Agregar env vars manualmente al run config de IntelliJ

---

## Proyecto: BedrockBridge

### Ubicación
`~/bedrockbridge`

### Origen
Clonado de `https://github.com/FabricMC/fabric-example-mod` (template oficial de Fabric). El `.git` original fue eliminado y se inicializó un repo nuevo con `git init` + `git branch -M main`. No tiene remote configurado todavía.

### Versiones (en `gradle.properties`)
```properties
minecraft_version=26.1.2
loader_version=0.19.2
loom_version=1.16-SNAPSHOT
fabric_api_version=0.149.0+26.1.2
mod_version=1.0.0
maven_group=com.example
```

### build.gradle (resumen)
- Fabric Loom 1.16.2 cargado correctamente
- `sourceCompatibility/targetCompatibility = JavaVersion.VERSION_25`
- `options.release = 25`
- Split environment source sets (main + client)
- Dependencias: minecraft, fabric-loader, fabric-api

### settings.gradle
```groovy
rootProject.name = 'bedrockbridge'
```
(Cambiado desde `'modid'`)

### Estructura del proyecto

```
~/bedrockbridge/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew, gradlew.bat
├── src/
│   ├── main/
│   │   ├── java/com/example/
│   │   │   └── BedrockBridge.java        ← Entrypoint común
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       ├── bedrockbridge.mixins.json
│   │       └── assets/
│   └── client/
│       ├── java/com/example/client/
│       │   └── BedrockBridgeClient.java  ← Entrypoint cliente
│       └── resources/
│           └── bedrockbridge.client.mixins.json
└── build/libs/
    ├── bedrockbridge-1.0.0.jar
    └── bedrockbridge-1.0.0-sources.jar
```

### fabric.mod.json (campos clave)
- `id`: `"bedrockbridge"`
- `name`: `"BedrockBridge"`
- `entrypoint main`: `com.example.BedrockBridge`
- `entrypoint client`: `com.example.client.BedrockBridgeClient`
- `depends`: `fabricloader>=0.19.2`, `minecraft~26.1.2`, `java>=25`, `fabric-api*`

### Decisión pendiente: renombrar paquete
Las clases Java siguen en `com.example` y `com.example.client`. Originalmente queríamos renombrarlas a algo como `com.dnixxv.bedrockbridge` pero lo dejamos para después para no introducir bugs ahora. **Por ahora dejar así.**

---

## Plan completo del proyecto

### ✅ Etapa A: Hello World — COMPLETADA

- Mod compila con `./gradlew build` (BUILD SUCCESSFUL en ~1m primera vez, ~4s después)
- Mod compila y se carga con `./gradlew runClient`
- Logger imprime mensajes desde código mío:
  - `"¡BedrockBridge cargado! Listo para conectar Java y Bedrock."`
  - `"BedrockBridgeClient inicializado en el lado del cliente."`
- Minecraft 26.1.2 abre y muestra `(Modded)` en el menú principal
- Mod aparece en la lista interna de Fabric (51 mods cargados en total, incluyendo `bedrockbridge 1.0.0`)

### 🚧 Etapa B: Detectar evento "Open to LAN" — EN PROGRESO

**Objetivo**: cuando el usuario abre su mundo a LAN (`Esc → Open to LAN → Start LAN World`), el mod debe:

1. Detectar el momento exacto
2. Imprimir log con el puerto Java asignado
3. Mostrar mensaje en el chat del jugador

**Código ya escrito en `BedrockBridgeClient.java`** (versión actual del archivo, recién editada):

```java
package com.example.client;

import com.example.BedrockBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class BedrockBridgeClient implements ClientModInitializer {

    private boolean lanWasOpen = false;

    @Override
    public void onInitializeClient() {
        BedrockBridge.LOGGER.info("BedrockBridgeClient inicializado en el lado del cliente.");
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        boolean lanIsOpen = client.hasSingleplayerServer()
                && client.getSingleplayerServer() != null
                && client.getSingleplayerServer().isPublished();

        if (lanIsOpen && !lanWasOpen) {
            onLanOpened(client);
        }
        if (!lanIsOpen && lanWasOpen) {
            onLanClosed(client);
        }
        lanWasOpen = lanIsOpen;
    }

    private void onLanOpened(Minecraft client) {
        int port = client.getSingleplayerServer().getPort();
        BedrockBridge.LOGGER.info("¡Mundo abierto a LAN! Puerto Java: {}", port);

        if (client.player != null) {
            client.player.displayClientMessage(
                Component.literal("§a[BedrockBridge] §rMundo abierto a LAN en puerto §e" + port +
                    "§r. Aquí es donde arrancaríamos Geyser."),
                false
            );
        }
    }

    private void onLanClosed(Minecraft client) {
        BedrockBridge.LOGGER.info("Mundo LAN cerrado.");
        if (client.player != null) {
            client.player.displayClientMessage(
                Component.literal("§c[BedrockBridge] §rMundo LAN cerrado."),
                false
            );
        }
    }
}
```

**Verificación pendiente**:

- Confirmar que IntelliJ no marca errores rojos en los imports (clases `Minecraft`, `Component`, `ClientTickEvents` pueden tener variantes según versión de mappings)
- Compilar con `./gradlew runClient`
- Probar abriendo un mundo single-player y dándole "Open to LAN"
- Verificar que aparece el mensaje en el chat de Minecraft

**Si las clases no se resuelven**, verificar:

- `Minecraft`: puede ser `net.minecraft.client.Minecraft` (Mojang mappings) o `net.minecraft.client.MinecraftClient` (Yarn mappings)
- `Component`: puede ser `net.minecraft.network.chat.Component` (Mojang) o `net.minecraft.text.Text` (Yarn)
- Este template parece usar Mojang mappings (Component, Minecraft)

### 📋 Etapa C: Agregar botón en la UI de "Open to LAN"

- Modificar la pantalla `ShareToLanScreen` con un Mixin o Screen Event
- Agregar checkbox/botón **"Compartir con Bedrock"**
- Por ahora no hace nada al presionarlo

### 📋 Etapa D: Geyser + Floodgate embebidos

- Agregar Geyser como dependencia del mod (Maven de GeyserMC)
- Agregar Floodgate como dependencia
- Arrancar Geyser programáticamente cuando se presione el botón
- Configurar Floodgate `auth-type` automáticamente
- Manejar ciclo de vida (apagar Geyser cuando se cierra el LAN)

### 📋 Etapa E: Integración con Playit.gg ⭐

- Esto es lo clave para que funcione con personas en otras redes WiFi
- Auto-crear cuenta de Playit o usar API key
- Arrancar un túnel UDP en el puerto 19132 desde el mod
- Obtener URL pública (ej. `xxx.joinmc.link`)
- Mostrar al usuario la dirección para compartir

### 📋 Etapa F: UI bonita + manejo de errores

- Pantalla con la IP/puerto pública
- Botón **"Copiar al portapapeles"**
- QR code opcional
- Mensajes de error claros si algo falla

---

## Ya probado funcionando (configuración manual, NO el mod)

En otra instancia de Prism llamada **"Crossplay-Test"** tengo:

- Minecraft 26.1.2 + Fabric
- Geyser-Fabric (instalado manualmente desde geysermc.org)
- Floodgate-Fabric (instalado manualmente)
- Fabric API 0.149.0+26.1.2

Configurado con:

- `config.yml` de Geyser: `bedrock.port: 19132`, `java.auth-type: floodgate`
- Floodgate generó su `key.pem` automáticamente
- Firewall de Fedora: puerto `19132/udp` abierto

Probé conexión exitosa de Bedrock (Pocket Edition en celular) al mundo Java vía LAN local. Mi IP local es `192.168.1.218` en `wlo1` (WiFi). Mensaje `"Started Geyser on UDP port 19132"` aparece en chat al abrir LAN.

**Esto confirma que el stack Geyser+Floodgate funciona perfecto en 26.1.2. Ahora hay que empaquetarlo todo dentro del mod.**

---

## Decisiones tomadas que NO hay que reabrir

- Sí usar **Fabric** (no Forge ni NeoForge)
- Sí usar **Java 25** en el proyecto (aunque oficialmente Fabric pide 21+)
- Mod **100% cliente**, no server-side (al menos por ahora)
- Sí integrar **Playit.gg** (no obligar al usuario a abrir puertos del router)
- Sí dejar paquete `com.example` por ahora (renombrar después)
- Sí mantener IntelliJ Flatpak por ahora + lanzar con `gradle runClient`

---

## Lo que quiero ahora

Continuar con la **Etapa B**. Específicamente:

1. Verifica que el código actual de `BedrockBridgeClient.java` compile bien
2. Si los imports están mal (por mappings), corrígelos
3. Corre `./gradlew runClient` y verifica que abre Minecraft
4. Guíame para probar: crear mundo single-player, abrirlo a LAN, verificar que aparece el mensaje en chat
5. Si funciona, pasamos a la Etapa C

---

## Comandos útiles de referencia

```bash
# Compilar el mod
cd ~/bedrockbridge
./gradlew build

# Lanzar Minecraft con el mod cargado (workflow normal)
./gradlew runClient

# Limpiar y recompilar
./gradlew clean build

# Regenerar sources decompilados de Minecraft (para autocompletado)
./gradlew genSources

# Ver el .jar generado
ls -la build/libs/

# Ver IP local
ip addr show | grep "inet " | grep -v 127.0.0.1
```

---

## Primer paso al iniciar sesión de Claude Code

Por favor primero **lee estos archivos** para confirmar el estado actual antes de proponer cambios:

- `~/bedrockbridge/gradle.properties`
- `~/bedrockbridge/build.gradle`
- `~/bedrockbridge/settings.gradle`
- `~/bedrockbridge/src/main/resources/fabric.mod.json`
- `~/bedrockbridge/src/main/java/com/example/BedrockBridge.java`
- `~/bedrockbridge/src/client/java/com/example/client/BedrockBridgeClient.java`

Y luego dame el siguiente paso para completar la **Etapa B**.
