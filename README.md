# BedrockBridge

Mod Fabric para Minecraft Java que permite a jugadores de **Minecraft Bedrock** (celular, consola, tablet, Windows 10) conectarse a tu mundo single-player **con un solo botón**. Sin port-forwarding, sin configurar nada, sin cuenta de Microsoft en el invitado.

Tildás "Compartir con Bedrock" en la pantalla de "Open to LAN" y BedrockBridge se encarga del resto:
1. Arranca **Geyser** para traducir el protocolo Java ↔ Bedrock.
2. Arranca **Floodgate** para que los jugadores Bedrock entren sin tener cuenta Java.
3. Arranca un túnel **Playit.gg** UDP que expone el puerto 19132 a internet, así tu invitado se conecta desde cualquier red sin que abras puertos en tu router.

Te aparece el endpoint público en chat (ej. `means-confidentiality.gl.at.ply.gg:6740`); el invitado lo pega en MC Bedrock → Servers → Add Server y entra.

---

## Requisitos

- Minecraft Java **1.26.1.2** (alias 26.1.2).
- Fabric Loader **>= 0.19.2**.
- Java **25** runtime.
- Fabric API.
- Linux x86_64 (por ahora; Windows/macOS pendientes — el lado Java compila multi-plataforma pero el binario Playit que se baja es Linux only).

Las dependencias del mod (Geyser-Fabric, Floodgate-Fabric, binarios Playit) se descargan automáticamente — no hay que instalar nada a mano.

---

## Instalación (usuarios finales)

1. Bajá el `.jar` de BedrockBridge de la última release.
2. Copialo a `~/.minecraft/mods/` (o el `mods/` de tu launcher).
3. Asegurate de tener también `fabric-api` en `mods/`.
4. Arrancá Minecraft con el perfil Fabric.

En el **primer uso** abrir LAN con "Compartir con Bedrock" activo va a:
1. Descargar Geyser/Floodgate jars y binarios Playit (~25 MB total, una sola vez).
2. Mostrarte en chat un link **`https://playit.gg/claim/...`** para vincular tu cuenta Playit (gratis, no requiere tarjeta).
3. Abrís el link, hacés "Add Agent", te crea el túnel UDP, y te aparece el endpoint público en chat.

Próximas veces el endpoint aparece directo sin claim.

---

## Cómo usar

1. Carga tu mundo single-player.
2. **Esc** → **Open to LAN**.
3. Confirmá que **"Compartir con Bedrock"** esté tildado (default: sí).
4. **Start LAN World**.
5. En chat te aparecen 3 líneas, la última con la dirección pública para Bedrock.
6. Pasale ese endpoint a tu invitado: `Servers → Add Server → Name: lo que sea, Address: nombre.gl.at.ply.gg, Port: número`.
7. El invitado tocá el server, entra sin ningún prompt extra.

Para apagar: cerrá el mundo o **Save and Quit to Title**. BedrockBridge para Geyser y el túnel automáticamente.

Si destildás "Compartir con Bedrock" antes de abrir LAN, te quedás con LAN normal de Java solo (sin Geyser, sin Playit) — útil cuando solo querés jugar con amigos en la misma red local.

---

## Troubleshooting

### "Failed to retrieve profile key pair" en logs
Normal en desarrollo (cuenta dev de Fabric). Ignorable.

### El endpoint público no aparece en chat
- Revisá que tu PC tenga internet (Playit necesita conectarse a su cloud).
- Si la primera vez ves la URL de claim pero el endpoint no llega, abrí la URL y dale "Add Agent". El túnel se crea después.

### "Desconexión del anfitrión" en el celular tras unos segundos
- Si estás testeando con VPN (Cloudflare WARP, Proton free), eso es esperable — esas VPNs pierden paquetes UDP. Probá con datos móviles reales o pedile a alguien afuera que pruebe.

### "Playit API HTTP 401: not authorized"
- El secret de Playit está corrupto. Borrá `<gameDir>/bedrockbridge/playit.toml` y reabrí LAN — vas a tener que claimear de nuevo.

### Solo veo 51 mods cargados (debería haber 100+)
- El Floodgate jar no se está cargando. Verificá que `<gameDir>/bedrockbridge/` exista; si corrés desde IntelliJ con `runClient`, asegurate que la tarea Gradle `downloadEmbeddedJars` haya bajado los jars a `libs/`.

### Quiero cambiar de cuenta Playit
- Borrá `<gameDir>/bedrockbridge/playit.toml` y reabrí LAN para arrancar un claim nuevo.

---

## Build desde fuente

```bash
git clone <repo-url>
cd bedrockbridge
./gradlew build
```

La tarea `downloadEmbeddedJars` baja Geyser-Fabric y Floodgate-Fabric automáticamente con SHA256/SHA512 pinneado (no se commitean al repo). El `.jar` final queda en `build/libs/`.

Para correr el cliente Minecraft con el mod cargado en dev:
```bash
./gradlew runClient
```

---

## Arquitectura

- **`com.example.BedrockBridge`** — mod entrypoint, init liviano.
- **`com.example.BedrockBridgePreLaunch`** — corre ANTES que Geyser para escribir `config/Geyser-Fabric/config.yml` con `auth-type: floodgate`.
- **`com.example.client.BedrockBridgeClient`** — registra el checkbox en `ShareToLanScreen` y polea LAN state via `ClientTickEvents`.
- **`com.example.state.BedrockBridgeState`** — flag `shareWithBedrock` compartido entre cliente y server thread.
- **`com.example.mixin.GeyserModBootstrapMixin`** — Mixin que cancela `onGeyserEnable()` si el flag está false, así el checkbox apaga Geyser de verdad.
- **`com.example.playit.PlayitBinaries`** — descarga lazy de `playit-linux-amd64` + `playit-cli-linux-amd64` v1.0.4 con SHA256 verificado, a `<gameDir>/bedrockbridge/bin/`.
- **`com.example.client.playit.PlayitManager`** — orquesta claim flow, lanza el daemon como subproceso, lee stdout en hilo aparte.
- **`com.example.client.playit.PlayitApi`** — cliente REST mínimo contra `api.playit.gg` para crear/listar tunnels.

Geyser-Fabric + Floodgate-Fabric se embeben como nested JIJ vía el bloque `jar` de `build.gradle` y `fabric.mod.json`'s `jars` array.

---

## Créditos

BedrockBridge es solo el pegamento; el trabajo pesado lo hacen:

- **[GeyserMC/Geyser](https://github.com/GeyserMC/Geyser)** (MIT) — traducción de protocolos Bedrock ↔ Java.
- **[GeyserMC/Floodgate-Modded](https://github.com/GeyserMC/Floodgate-Modded)** (MIT) — autenticación de jugadores Bedrock sin cuenta Java.
- **[playit-cloud/playit-agent](https://github.com/playit-cloud/playit-agent)** (BSD-2) — túnel UDP a internet sin port-forwarding.
- **API client schema** adaptado de [maxomatic458/playit-minecraft-mod](https://github.com/maxomatic458/playit-minecraft-mod) (BSD-2, archivado).

---

## License

CC0 1.0 Universal. Ver `LICENSE`.
