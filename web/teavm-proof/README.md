# Prueba mínima de TeaVM

Esta carpeta comprueba si una pequeña parte de la lógica Java puede ejecutarse
en el navegador después de compilarse con TeaVM WebAssembly GC. No depende de
Swing, AWT ni del Mario AI Framework todavía.

## Requisitos

- JDK 25 o superior.
- Maven 3.9 o superior.
- Un navegador moderno.

El JDK y Maven sólo se necesitan para desarrollar o regenerar la prueba:
TeaVM usa el JDK y Maven para convertir el código Java en
`target/web/classes.wasm`. El navegador carga ese archivo WebAssembly, por lo
que quienes sólo vayan a jugar una versión ya compilada no necesitan instalar
Java, Maven ni TeaVM.

Puedes instalar las herramientas del sistema desde la carpeta `src/` del
repositorio:

```powershell
make install-web-tools
```

En Windows usa `winget` para JDK 25 y descarga Maven desde su distribución
oficial a `%LOCALAPPDATA%\MarioAiTools\`, agregándolo de forma permanente al
`PATH` del usuario (instalación global, no depende del repositorio ni se
borra con `git clean`). En Linux usa `apt-get` y en macOS usa Homebrew, que
instalan Maven globalmente en el sistema. Después de la instalación, abre una
terminal nueva si se instaló JDK 25 o Maven por primera vez para que la nueva
sesión vea el `PATH` actualizado.

## Compilar

Desde la carpeta `src/` del repositorio, con Maven instalado:

```powershell
make web-proof
```

El target compila, prepara `target/web/` y levanta un servidor HTTP local en
`http://localhost:8080/`. Maven descarga las dependencias de TeaVM
automáticamente; no tienes que copiar jars ni ejecutar varios comandos.

El flujo de compilación es:

```text
Java + JDK + Maven + TeaVM -> classes.wasm -> navegador
```

### Flujo completo

Al ejecutar `make web-proof` desde `src/`, ocurre lo siguiente:

```text
┌──────────────────────────────┐
│ make web-proof                │
└──────────────┬───────────────┘
               │ delega según el sistema operativo
               v
┌──────────────────────────────┐
│ run-proof.ps1 / run-proof.sh │
└──────────────┬───────────────┘
               │ ejecuta Maven package
               v
┌──────────────────────────────┐
│ pom.xml                      │
│ configuración del proyecto   │
└──────────────┬───────────────┘
               │ Maven usa JDK y TeaVM
               v
┌──────────────────────────────┐
│ MarioSimulation.java         │
│ Java compatible              │
└──────────────┬───────────────┘
               │ TeaVM compila una vez
               v
┌──────────────────────────────┐
│ classes.wasm                 │
│ lógica Java compilada        │
└──────────────┬───────────────┘
               │ el script prepara los archivos
               v
┌─────────────────────────────────────────────┐
│ target/web/                                 │
│                                             │
│  index.html                                 │
│  classes.wasm                               │
│  wasm-gc-module-runtime.js                  │
│  level.txt                                  │
└──────────────────────┬──────────────────────┘
                       │ servidor HTTP local
                       v
┌─────────────────────────────────────────────┐
│ navegador: http://localhost:8080/           │
│                                             │
│ index.html                                  │
│   ├─ carga el runtime de TeaVM              │
│   ├─ carga classes.wasm                      │
│   ├─ lee level.txt                           │
│   ├─ recibe el teclado                       │
│   └─ dibuja el Canvas                       │
│                                             │
│ wasm-gc-module-runtime.js                   │
│   └─ conecta el módulo WebAssembly          │
│                                             │
│ classes.wasm                                │
│   └─ ejecuta step(), reset(), getX(), etc.  │
└─────────────────────────────────────────────┘
```

La compilación ocurre antes de abrir la página. Al pulsar una tecla no se
vuelve a traducir Java: el JavaScript de `index.html` llama funciones que ya
están compiladas dentro de `classes.wasm`, y después redibuja el Canvas.

El runtime de TeaVM no es un reloj ni controla por sí solo el tiempo de la
partida. Es el adaptador JavaScript que carga `classes.wasm` y prepara la
conexión entre el módulo WebAssembly y el navegador. El JavaScript que maneja
el teclado, la cámara y el dibujo del Canvas está escrito en `index.html`.

## Probar

No abras el archivo con `file://`, porque el navegador puede bloquear módulos
JavaScript y WebAssembly por políticas de seguridad.

La prueba valida cuatro cosas:

1. Java se compila con TeaVM.
2. El navegador puede invocar métodos Java exportados.
3. Java puede parsear el contenido de un nivel de texto sin usar Swing.
4. El navegador puede dibujar los símbolos del nivel en Canvas.

El target no inventa un formato nuevo ni usa un nivel artificial: toma el
primer `.txt` disponible de `src/levels/nivel0/` y lo copia como `level.txt`
al directorio web. En el despliegue, ese archivo será sustituido por el nivel
filtrado o generado que entregue el backend, sin cambiar el parser ni el
formato.
