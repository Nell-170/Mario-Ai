# Generación Adaptativa y Personalizada de Niveles de Super Mario con Inteligencia Artificial

Proyecto de investigación desarrollado en la Escuela de Ciencias de la Computación e Informática (ECCI) de la Universidad de Costa Rica (UCR) como Trabajo Final de Graduación (TFG).

El sistema implementa un entorno experimental completo para evaluar la experiencia de juego del usuario frente a diferentes métodos de diseño de niveles en plataformas 2D, comparando niveles creados por humanos (*Super Mario Maker 2*), niveles genéricos prediseñados y niveles generados proceduralmente de forma adaptativa mediante modelos de lenguaje profundo ([MarioGPT](https://github.com/shyamsn97/mario-gpt)) condicionados por la telemetría en tiempo real y razonamiento semántico con modelos de lenguaje masivos ([Ollama](https://ollama.ai/)).

---

## 🎮 Pipeline de Generación Adaptativa

```
                     ┌────────────────────────────────────────────────────────┐
                     │                                                        │
                     ▼                                                        │
┌─────────────────────────┐         ┌──────────────────────────┐              │
│       Participante      │         │   Extracción Semántica   │              │
│     (Sesión de Juego)   │────────▶│      y Razonamiento      │              │
└────────────┬────────────┘         │      (Ollama / LLM)      │              │
             │                      └─────────────┬────────────┘              │
             │ Telemetría                         │                           │
             ▼                                    ▼ Prompt Condicionado       │
┌─────────────────────────┐         ┌──────────────────────────┐              │
│  Métricas de Jugabilidad│         │    Modelo Generativo     │              │
│   (Saltos, bajas, ruta, │         │         MarioGPT         │              │
│   tiempo, daño, monedas)│         │     (GPT-2 fine-tuned)   │              │
└─────────────────────────┘         └─────────────┬────────────┘              │
                                                  │                           │
                                                  ▼ Nivel ASCII generado      │
                                    ┌──────────────────────────┐              │
                                    │ Validación de Jugabilidad│              │
                                    │    Agente Heurístico A*  │──────────────┘
                                    │    (robinBaumgarten)     │  Nivel Jugable
                                    └──────────────────────────┘
```

El pipeline opera a través de los siguientes componentes integrados:
1. **Motor de simulación y juego:** [Mario AI Framework](https://github.com/amidos2006/Mario-AI-Framework) (Java), optimizado con parches locales para soportar monitores modernos en alta resolución, audio procedural por síntesis, soporte universal de mandos y telemetría de precisión.
2. **Dataset de Mario Maker 2:** Filtrado por transmisión continua (*streaming*) desde Hugging Face (`TheGreatRambler/mm2_level`) y análisis binario mediante Kaitai Struct.
3. **Conversor MM2 → MAF:** Transformación de formatos binarios a grillas matriciales de caracteres ASCII compatibles tanto con el motor de juego como con los *tokens* de MarioGPT.
4. **Validación automática de jugabilidad:** Agente de búsqueda heurística **A\*** (*robinBaumgarten*) ejecutado en paralelo de manera no gráfica (*headless*) para garantizar que ningún nivel imposible sea presentado a los participantes.
5. **Telemetría y personalización:** Captura continua de eventos (tiempo consumido, porcentaje de avance, saltos, bajas de enemigos, daños recibidos y coordenadas de muerte, recolección de monedas, velocidad promedio y preferencia estimada de ruta vertical).
6. **Razonador LLM (Ollama):** Interpretación de las métricas de juego del participante para modular el prompt de generación (ejemplo: número de enemigos, tuberías, bloques y elevación del terreno).
7. **Generador MarioGPT:** Red neuronal basada en GPT-2 para emitir estructuras de niveles personalizadas.
8. **Base de datos centralizada:** Persistencia en PostgreSQL para el análisis psicométrico y estadístico de las sesiones.

---

## 🔬 Protocolo y Flujo del Experimento

El protocolo experimental está diseñado bajo un formato riguroso de **Cuadrado Latino balanceado (6×6)** para mitigar efectos de orden, fatiga y aprendizaje. Cuando usted ejecute el experimento (`make start-experiment`), el sistema guiará al participante a través de las siguientes etapas secuenciales:

```
[1. Bienvenida e Inducción]
           │
           ▼
[2. Niveles de Familiarización (2 niveles de práctica)] ──▶ (Captura de Telemetría Basal)
           │                                                               │
           ▼                                                               ▼
[3. Consentimiento Informado]                             (Encolamiento de generación IA)
           │
           ▼
[4. Cuestionario Inicial (Demografía y Experiencia)]
           │
           ▼
┌────────────────────────────────────────────────────────────────────────┐
│ [5. Bloque Principal: Cuadrado Latino (6 niveles de juego)]            │
│                                                                        │
│  Para cada nivel (1 a 6):                                              │
│    a) Presentación con silueta/miniatura ampliada y esquema de mandos  │
│    b) Ejecución de la partida con captura de telemetría                │
│    c) Cuestionario de Nivel inmediato (Escala Likert de 7 puntos)      │
│                                                                        │
│  Composición de los 6 niveles:                                         │
│    • 2 Niveles Personalizados (IA adaptativa MarioGPT + Ollama)        │
│    • 2 Niveles Genéricos (No personalizados)                           │
│    • 2 Niveles Humanos (Seleccionados del dataset Nivel 0)             │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
[6. Fase de Cierre: Ranking de Niveles y Comentarios Cualitativos]
                                   │
                                   ▼
[7. Fase Voluntaria de Juego Libre (Free Play - hasta 20 minutos)]
                                   │
                                   ▼
[8. Agradecimiento Final y Sincronización en Base de Datos]
```

### Detalle de cada fase del flujo:

1. **Pantalla de Bienvenida e Instrucciones:**
   Se introduce al participante al estudio, informándole sobre la estructura general de la sesión (etapa de práctica, niveles de prueba, cuestionarios breves y juego libre voluntario al concluir).
2. **Niveles de Familiarización (Práctica Previa):**
   El participante juega dos niveles breves especialmente seleccionados para acostumbrarse a la inercia, la velocidad de desplazamiento y la física de salto de Mario.
   - *Calibración basal:* La telemetría capturada en estos dos niveles se procesa inmediatamente en segundo plano para inicializar los prompts de Ollama y poner a generar los niveles personalizados antes de que inicie el bloque formal.
3. **Consentimiento Informado:**
   Se presentan en pantalla los términos del estudio, el carácter voluntario de su participación y el tratamiento confidencial y anónimo de los datos recopilados.
   - Si el participante no acepta el consentimiento, el sistema cancela el registro formal y le ofrece la opción de jugar libremente por recreación o cerrar el juego de forma inmediata sin almacenar información en la base de datos.
4. **Cuestionario Inicial:**
   Se recopilan datos demográficos y de perfil de juego: edad, dedicación semanal a videojuegos generales y a plataformas en 2D, experiencia previa con títulos de la saga Super Mario y tipo de control utilizado habitualmente. Al completar esta sección, el participante queda formalmente inscrito en la base de datos.
5. **Bloque Principal del Experimento (6 niveles):**
   El participante enfrenta los 6 niveles según la secuencia asignada por su grupo del Cuadrado Latino (grupos `S1` a `S6`).
   - **Tipos de nivel evaluados:** Dos personalizados generados por IA (tipo A / `P`), dos genéricos (tipo B / `N`) y dos diseñados por humanos (tipo C / `H`).
   - **Pantalla previa:** Antes de entrar a cada nivel, se despliega una ventana de alta resolución con la silueta completa del mapa y los controles mapeados tanto para teclado como para mando (PlayStation / Xbox).
   - **Cuestionario por nivel:** Al concluir cada nivel (por victoria, muerte o límite de tiempo), el participante responde de inmediato seis preguntas en escala Likert horizontal de 7 puntos (desde `-3 = Totalmente en desacuerdo` hasta `+3 = Totalmente de acuerdo`): disfrute general, competencia percibida, dificultad subjetiva, adecuación del reto, variedad de elementos y deseo de rejugar niveles similares.
6. **Fase de Cierre y Ranking Comparativo:**
   Con las experiencias de juego aún frescas en la memoria, el participante ordena visualmente los 6 niveles jugados de mayor a menor preferencia mediante una interfaz asistida por las miniaturas de cada nivel, registrando además comentarios libres sobre las razones de su elección.
7. **Fase Voluntaria de Juego Libre (*Free Play*):**
   Se evalúa la motivación intrínseca del participante ofreciéndole la posibilidad de jugar niveles adicionales durante un tiempo máximo de 20 minutos o retirarse si lo prefiere. El tiempo de permanencia y la cantidad de niveles jugados se almacenan como indicadores de compromiso voluntario.
8. **Finalización y Sincronización:**
   Se presenta la pantalla de agradecimiento y el sistema transmite todas las métricas de telemetría, respuestas de cuestionarios, orden del ranking y eventos de juego al servidor central PostgreSQL.

---

## 📁 Estructura del Proyecto

```
Mario-Ai/
├── mario_ai_framework/               # Motor de simulación Mario AI Framework (Java)
│   ├── src/                          # Código fuente del motor con parches aplicados
│   └── bin/                          # Clases Java compiladas (.class)
├── mario_gpt/                        # Modelo de lenguaje generativo MarioGPT (PyTorch)
├── src/                              # Código fuente, herramientas y datos del proyecto
│   ├── dataset/                      # Extracción y procesamiento de datos
│   │   ├── filter_mm2.py             # Descarga y filtrado streaming desde Hugging Face
│   │   ├── convert_mm2_to_maf.py     # Conversión de binario MM2 a matrices ASCII
│   │   ├── level.py / level.ksy      # Estructura del parser binario Kaitai Struct
│   │   └── mm2_selected.csv          # Metadatos de niveles seleccionados
│   ├── experiment/                   # Configuración y persistencia del experimento
│   │   ├── config.properties         # Configuración general (modos, timer, audio, debug)
│   │   ├── preguntas.tsv             # Banco de preguntas de cuestionarios (inicial y por nivel)
│   │   ├── db/                       # Esquemas SQL y utilidades de base de datos PostgreSQL
│   │   ├── db_pending/               # Cola de respaldo local ante desconexión de red
│   │   └── results/                  # Carpetas locales por participante con telemetría y niveles
│   ├── levels/                       # Bancos de niveles organizados por categoría
│   │   ├── familiarization/          # Niveles de práctica preliminar
│   │   ├── generics/                 # Niveles genéricos prediseñados
│   │   ├── nivel0/                   # Niveles humanos validados por A* (Nivel 0)
│   │   ├── generated/                # Niveles generados de forma individual / libre
│   │   └── converted/                # Niveles convertidos de Mario Maker 2
│   ├── telemetry/                    # Archivos de telemetría de sesiones libres
│   ├── tools/                        # Herramientas de software y scripts auxiliares
│   │   ├── ExperimentRunner.java     # Motor de ejecución del experimento guiado
│   │   ├── PlayHuman.java            # Probador de nivel individual para humanos con telemetría
│   │   ├── LevelSelector.java        # Selector gráfico Swing para explorar y jugar niveles
│   │   ├── ValidateLevels.java       # Validador paralelo con agente A* inteligente
│   │   ├── dashboard.py              # Servidor web local de monitoreo y visualización
│   │   ├── dashboard_ui/             # Interfaz web interactiva (HTML5, CSS3, JS)
│   │   ├── setup_lab_machine.py      # Instalador y verificador unificado para laboratorios
│   │   ├── mario_gpt_generate.py     # Inferencia de MarioGPT (Local, Servidor o HPC)
│   │   ├── telemetry_to_mariogpt_prompt.py # Traducción de telemetría a prompt descriptivo
│   │   ├── framework.patch           # Parche acumulativo para el motor de Mario AI
│   │   └── engine/                   # Módulos inyectados (audio procedural, gamepad, HUD)
│   ├── gamepad.properties            # Mapeo de botones y calibración de mandos
│   ├── Makefile                      # Automatización de tareas de compilación y ejecución
│   └── requirements.txt              # Requisitos de bibliotecas de Python
└── README.md
```

---

## ⚙️ Requisitos Previos

| Requisito | Versión Recomendada | Finalidad |
|---|---|---|
| **Java JDK** | 17 LTS o superior (probado en 17, 21 y 25) | Compilación y ejecución del motor gráfico Mario AI Framework y herramientas Swing |
| **Python** | 3.10 o superior | Procesamiento de datos, servidor web, puente Ollama y MarioGPT |
| **Git** | 2.x | Clonación y control de versiones |
| **GNU Make** | 3.81 o superior | Automatización de compilación y ejecución unificada (`make`) |
| **PostgreSQL** *(Opcional para desarrollo)* | 14 o superior | Persistencia de datos del experimento (cuenta con respaldo local offline) |

---

## 🚀 Puesta a Punto e Instalación Rápida

Usted puede inicializar todo el entorno ejecutando los siguientes comandos en su terminal:

```bash
# Navegar a la carpeta fuente
cd src

# 1. Clonar repositorios requeridos e instalar dependencias
make setup
make setup-mariogpt
make install-deps
make install-mariogpt

# 2. Compilar el framework Java y aplicar parches
make compile
```

> 💡 **Instalador automático para computadoras de laboratorio:**  
> Si usted está preparando un equipo para la aplicación presencial de pruebas, puede ejecutar:
> ```bash
> python3 tools/setup_lab_machine.py
> ```
> Dicho script verificará la versión de Java, configurará el entorno virtual de Python, compilará el código y validará la conectividad con el servidor de generación y la base de datos.

---

## 🖥️ Panel de Control Web y Modo Kiosco

El proyecto incorpora un tablero de control web local desarrollado para facilitar la administración y monitoreo de las pruebas sin necesidad de manipular la terminal durante la sesión:

```bash
# Iniciar el servidor web local en el puerto 5050
make dashboard
# o alternativamente:
make gui
```

Una vez iniciado, usted puede abrir su navegador en: **`http://localhost:5050`**

### Características del Tablero de Control:
- **Estadísticas globales:** Cantidad de participantes evaluados, sesiones registradas y banco de niveles por categoría.
- **Visualizador 2D interactivo:** Inspección en lienzo gráfico (*canvas*) de los bloques y componentes de cualquier nivel, incluyendo los generados proceduralmente dentro de las carpetas de los participantes (`src/experiment/results/*/generated/`).
- **Probador directo:** Botón para lanzar cualquier nivel seleccionado directamente en el motor de juego.
- **Pantalla de Recibidor (Modo Kiosco):** Accesible en `http://localhost:5050/recibidor.html` (o mediante `make kiosk` / `make recibidor`), ofrece una vista de espera a pantalla completa para participantes que inicia el experimento con un solo clic y retorna automáticamente al finalizar.

---

## 🕹️ Controles, Mandos y Diagnóstico en Pantalla

### 1. Esquema de Teclado
- **Desplazamiento:** Flechas del teclado o teclas `A` / `D` (Izquierda / Derecha).
- **Agacharse:** Flecha abajo (`↓`) o tecla `S`.
- **Salto:** Barra espaciadora, tecla `F` o flecha arriba (`↑`).
- **Correr / Bola de fuego:** Tecla `Shift`, tecla `D` o tecla `J`.
- **Pausa:** Tecla `P`.
- **Reinicio rápido de nivel:** Tecla `R`.
- **Silenciar audio:** Tecla `M`.
- **Diagnóstico de controles (HUD):** Tecla **`F3`**.

### 2. Soporte para Mandos (PlayStation / Xbox)
El motor detecta automáticamente controles USB y Bluetooth conectados mediante el módulo nativo [`GamepadManager`](file:///home/marioc/Desktop/UCR/Mario-Ai/src/tools/engine/input/GamepadManager.java).
- **Mando PlayStation (PS4 / PS5):**
  - **`✖ Equis`**: Saltar.
  - **`⬛ Cuadrado` / `⭕ Círculo` / `R1` / `L1`**: Correr y lanzar bolas de fuego.
  - **`Cruceta (D-Pad)` o Stick izquierdo**: Desplazamiento horizontal y agacharse.
  - **`Options`**: Pausa.
  - **`Share`**: Reiniciar nivel.
- **Mando Xbox (Xbox 360 / One / Series):**
  - **`A`**: Saltar.
  - **`X` / `B` / `RB` / `LB`**: Correr y lanzar bolas de fuego.
  - **`Start`**: Pausa.
  - **`Back`**: Reiniciar nivel.

Usted puede personalizar este mapeo editando [`src/gamepad.properties`](file:///home/marioc/Desktop/UCR/Mario-Ai/src/gamepad.properties).

### 3. Diagnóstico de Teclas y Mando en Tiempo Real
Si usted desea verificar qué botones, teclas o ejes analógicos están enviando señal a Mario para comprobar posibles teclas trabadas o calibración de mandos:
- Active la bandera en [`src/experiment/config.properties`](file:///home/marioc/Desktop/UCR/Mario-Ai/src/experiment/config.properties):
  ```properties
  debug_input=true
  ```
- O presione la tecla **`F3`** en cualquier momento durante la partida.
Se desplegará una superposición (*HUD*) en pantalla indicando las 5 acciones de Mario en colores en tiempo real, las teclas físicas oprimidas y los valores numéricos de los ejes del mando.

---

## 🧠 Modos de Generación con IA (MarioGPT)

Usted puede definir la modalidad de generación en [`src/experiment/config.properties`](file:///home/marioc/Desktop/UCR/Mario-Ai/src/experiment/config.properties) (`generation_mode`) según los recursos de cómputo disponibles:

1. **`Nativo` (Local):**  
   Ejecuta el modelo MarioGPT directamente en la máquina local haciendo uso de PyTorch (apto para equipos con GPU dedicada o CPUs modernas).
2. **`JeissonServer` (Servidor HTTP remoto del laboratorio):**  
   Envía el prompt al servicio central de inferencia alojado en el servidor del laboratorio (`http://jeisson.ecci.ucr.ac.cr:8080`), permitiendo generar niveles en pocos segundos sin consumir recursos en la estación del participante.
3. **`Tunel` (Clúster HPC):**  
   Establece un túnel SSH cifrado hacia los nodos de cálculo del clúster institucional gestionados mediante Slurm.

---

## 📋 Catálogo de Comandos `make`

Todos los comandos deben ejecutarse desde el directorio `src/`:

| Comando | Descripción |
|---|---|
| `make start-experiment` | Inicia el protocolo experimental formal completo de Cuadrado Latino con generación IA |
| `make start-experiment-nogen` | Inicia el experimento en modo rápido (sustituye niveles de IA por niveles humanos de reserva) |
| `make play-human` | Ejecuta una partida individual con captura de telemetría (`telemetry/latest.json`) |
| `make play-level LEVEL="ruta"` | Prueba un archivo de nivel específico con presentación gráfica y miniatura |
| `make play-levels-generated` | Abre la interfaz gráfica Swing ([`LevelSelector`](file:///home/marioc/Desktop/UCR/Mario-Ai/src/tools/LevelSelector.java)) para elegir y jugar niveles |
| `make gui` o `make dashboard` | Lanza el servidor web local del panel de administración en el puerto 5050 |
| `make kiosk` o `make recibidor` | Abre el navegador en la pantalla de bienvenida para participantes (Modo Kiosco) |
| `make filter` | Descarga y filtra niveles de *Super Mario Maker 2* desde Hugging Face |
| `make convert` | Convierte niveles binarios MM2 a matrices ASCII estándar MAF |
| `make validate` | Valida jugabilidad mecánica de niveles convertidos mediante el agente A* paralelo |
| `make generate-level` | Genera un nivel procedural con MarioGPT a partir de la última telemetría registrada |
| `make play-and-generate` | Ejecuta el ciclo continuo: jugar un nivel y generar automáticamente el siguiente |
| `make clean` | Elimina los archivos binarios compilados de Java (`.class`) |
| `make clean-telemetry` | Limpia los registros locales de telemetría de juego |
| `make clean-levels` | Remueve niveles convertidos y temporales |
| `make clean-experiment-results`| Elimina datos y respuestas locales de experimentos (solicita confirmación) |
| `make clean-all` | Limpieza profunda de binarios, cachés y niveles temporales |
| `make help` | Muestra en terminal la lista de instrucciones disponibles |

---

## 🗄️ Base de Datos y Persistencia

El sistema almacena las sesiones de forma relacional en PostgreSQL. Si durante la sesión se pierde la conexión de red con el servidor de la base de datos, las transacciones se almacenan de manera transparente en archivos JSON dentro de `src/experiment/db_pending/` y se reintentan automáticamente en la siguiente ejecución para evitar pérdida de datos.

Para consultar detalles de conexión, esquemas y scripts de análisis estadístico, por favor revise la guía técnica en:  
👉 [`src/experiment/db/README.md`](./src/experiment/db/README.md).

---

## 🎵 Música y Efectos de Sonido Sintetizados

El motor incorpora síntesis de audio procedural en tiempo real escrita en Java puro:
- No requiere archivos de audio externos (MP3, WAV o sintetizadores de terceros).
- Incluye música dinámica de acompañamiento y efectos sonoros para saltos, recolección de monedas, impactos, daño, victoria y derrota.
- Usted puede activar o desactivar el subsistema de sonido ajustando `music=true` o `music=false` en [`src/experiment/config.properties`](file:///home/marioc/Desktop/UCR/Mario-Ai/src/experiment/config.properties) o pulsando la tecla **`M`** durante la partida.

---

## 📚 Agradecimientos y Descargo de Responsabilidad

Este proyecto se fundamenta en los siguientes proyectos y conjuntos de datos de código abierto:
- **[Mario AI Framework](https://github.com/amidos2006/Mario-AI-Framework)** — Desarrollado por Ahmed Khalifa, Julian Togelius et al. (Licencia MIT).
- **[MarioGPT](https://github.com/shyamsn97/mario-gpt)** — Desarrollado por Shyam Sudhakaran et al. (Licencia MIT).
- **[Dataset mm2_level](https://huggingface.co/datasets/TheGreatRambler/mm2_level)** — Publicado por TheGreatRambler en Hugging Face Datasets.

> ⚠️ **Aviso legal de propiedad intelectual (Disclaimer):**  
> Este es un proyecto de investigación académica y formativa sin fines comerciales. **No está afiliado, patrocinado, respaldado ni asociado con Nintendo Co., Ltd.** Las marcas comerciales *Super Mario*, *Super Mario Bros.*, *Super Mario Maker* y sus componentes audiovisuales asociados son propiedad intelectual exclusiva de Nintendo.
