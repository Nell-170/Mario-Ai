# Prueba mínima de TeaVM

Esta carpeta comprueba si una pequeña parte de la lógica Java puede ejecutarse
en el navegador después de compilarse con TeaVM WebAssembly GC. No depende de
Swing, AWT ni del Mario AI Framework todavía.

## Requisitos

- JDK 17 o superior.
- Maven 3.9 o superior.
- Un navegador moderno.

## Compilar

Desde esta carpeta, con Maven instalado:

```powershell
.\run-proof.ps1
```

El script compila, prepara `target/web/` y levanta un servidor HTTP local en
`http://localhost:8080/`. TeaVM produce el módulo `classes.wasm`; el runtime
oficial de TeaVM lo carga desde un módulo JavaScript y expone los métodos Java
exportados.

## Probar

No abras el archivo con `file://`, porque el navegador puede bloquear módulos
JavaScript y WebAssembly por políticas de seguridad.

La prueba valida tres cosas:

1. Java se compila con TeaVM.
2. El navegador puede invocar métodos Java exportados.
3. La lógica puede actualizar una representación HTML sin usar Swing.
