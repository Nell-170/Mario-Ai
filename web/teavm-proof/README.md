# Prueba mínima de TeaVM

Esta carpeta comprueba si una pequeña parte de la lógica Java puede ejecutarse
en el navegador después de compilarse con TeaVM WebAssembly GC. No depende de
Swing, AWT ni del Mario AI Framework todavía.

## Requisitos

- JDK 17 o superior.
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

En Windows usa `winget` para JDK 17 y descarga Maven desde su distribución
oficial a `web/teavm-proof/.tools/`. En Linux usa `apt-get` y en macOS usa
Homebrew. Después de la instalación, abre una terminal nueva si se instaló
JDK 17 para actualizar `PATH`.

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
