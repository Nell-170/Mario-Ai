Notas presentación

# Filmina 19
---

### 1. ¿Cómo funciona `filter_mm2.py` y por qué usamos *streaming*?

El dataset `TheGreatRambler/mm2_level` en Hugging Face contiene **~26.6 millones de niveles** organizados en 196 archivos Parquet (decenas de gigabytes comprimidos). Descargar todo el conjunto a disco local es inviable e ineficiente.

Por ello, el script utiliza **`streaming=True`**:
```python
ds = load_dataset("TheGreatRambler/mm2_level", streaming=True, split="train")
```
Esto descarga y procesa los registros **uno a uno directamente en memoria**. Si un nivel no cumple con algún criterio, se descarta de inmediato sin almacenar nada en disco.

---

### 2. Desglose de cada `if` en el filtro y reducción de niveles

A continuación se muestra el embudo secuencial de los `if` y la cantidad aproximada de niveles que sobreviven en cada paso:

```python
for rec in ds:
    # 1. ESTILO DE JUEGO
    if rec["gamestyle"] != args.gamestyle:            # ~26.6M → ~5.3M (20%)
        continue

    # 2. TASA DE VICTORIA (Dificultad de flujo)
    cr = rec["clear_rate"]
    if cr is None or cr < 30.0 or cr > 50.0:          # ~5.3M → ~580K (11%)
        continue

    # 3. CONFIABILIDAD ESTADÍSTICA
    attempts = rec["attempts"] or 0
    if attempts < 150:                                # ~580K → ~140K (24%)
        continue

    # 4. CALIDAD SUBJETIVA Y LONGITUD
    likes = rec.get("likes") or 0
    timer = rec.get("timer") or 0
    if likes < 30 or timer < 250:                     # ~140K → ~25K (18%)
        continue

    # 5. VIABILIDAD GEOMÉTRICA / MAF
    lines, stats = convert(parse_level(rec["level_data"]))
    if lines is None:                                 # ~25K → ~12K (48%)
        continue

    # 6. MIN-HEAP POR LIKES (Los mejores valorados)
    heapq.heappush(top_likes, (likes, rec))           # Retiene los Top N
```

#### Justificación técnica de cada filtro:
1. **`gamestyle != 0` (Super Mario Bros 1)**:
   - SMM2 cuenta con 5 estilos gráficos y de físicas (SMB1, SMB3, SMW, NSMBU, SM3DW).
   - Tanto *Mario AI Framework* (MAF) como *MarioGPT* operan exclusivamente sobre la representación y matriz de tiles de **SMB1**.
   - Descarta el 80% del dataset.
2. **`30.0 <= clear_rate <= 50.0` (Zona de flujo)**:
   - La mayoría de niveles en Mario Maker 2 son extremos: o imposibles/trolls con tasas de victoria < 2%, o automáticos ("no toques nada") con > 90%.
   - La ventana de 30% a 50% garantiza niveles que no resulten triviales ni frustrantes, ideales para evaluar el estado de flujo.
3. **`attempts >= 150` (Muestra representativa)**:
   - Si un nivel tiene 2 intentos y 1 victoria, su tasa de completitud es 50%, pero no es estadísticamente confiable. Requerir $\ge 150$ intentos evita falsos positivos por muestras ruidosas.
4. **`likes >= 30` y `timer >= 250 s`**:
   - `likes >= 30`: Filtra niveles de prueba o mal diseñados, conservando solo los que cuentan con validación positiva de jugadores humanos reales.
   - `timer >= 250`: Proxy para descartar niveles de una sola pantalla o desafíos de 3 segundos sin tener que decodificar el blob binario antes de tiempo.
5. **`convert(level)` (Viabilidad estructural MAF)**:
   - Valida en memoria que el nivel tenga suelo firme bajo Mario al aparecer (`spawn`), una meta alcanzable y sin vacíos insuperables.
6. **Min-Heap de Likes**:
   - Mantiene en memoria únicamente los niveles con mayor puntuación acumulada de likes.

---

### 3. ¿De dónde salen los niveles en `src/levels/nivel0` y `levels/familiarization`?

El flujo de creación de estos niveles es el siguiente:

```
[Hugging Face ~26.6M]
         │ (filter_mm2.py)
         ▼
[Candidatos filtrados: mm2_selected.csv]
         │ (convert_mm2_to_maf.py)
         ▼
[Niveles en texto MAF: levels/converted/]
         │ (ValidateLevels.java con Agente A* de Robin Baumgarten)
         ▼
[Niveles 100% Superables: levels/nivel0/]
```

1. **`levels/nivel0/` (14 niveles finales)**:
   - Son niveles de Mario Maker 2 convertidos a texto plano que fueron testeados por el agente inteligente **A* de Robin Baumgarten**.
   - Solamente si el agente A* logra llegar a la meta dentro del tiempo límite, el nivel se guarda en `src/levels/nivel0/`.
   - **¿Para qué sirven?**: Representan la **Condición H (Humanos)** en el bloque principal del experimento. Cuando el participante juega los 6 niveles en orden aleatorio balanceado (ej: `P1, H1, N1, P2, H2, N2`), los niveles `H` se toman aleatoriamente de esta carpeta para comparar objetivamente contra los niveles generados por IA (`P`).
2. **`levels/familiarization/` (Niveles de familiarización)**:
   - Contiene los dos niveles con mayor aprobación de la comunidad del dataset: `mm2_3000330.txt` (1,312 likes) y `mm2_3001683.txt` (653 likes).
   - **¿Para qué sirven?**: Se juegan en la fase inicial de práctica para que el participante se acostumbre a los controles (teclado o mando), mientras el sistema registra silenciosamente su **telemetría basal** (velocidad de avance, frecuencia de saltos, muertes), la cual luego alimenta a Ollama + MarioGPT para generar los niveles personalizados.

---

### 4. Preparación de defensa: ¿Qué responder ante el jurado? (Niveles de Hugging Face)

- **¿Por qué gamestyle == 0 (SMB1)?**
  MarioGPT y el simulador Mario-AI se basan exclusivamente en las mecánicas y el conjunto de tiles de *Super Mario Bros 1*. Los otros estilos de SMM2 añaden mecánicas como agarrar y lanzar objetos, giros en el aire o potenciadores ajenos (Mario ardilla, gato, etc.) que romperían la comparabilidad experimental con los niveles generados por la IA.

- **¿Por qué clear_rate entre 30% y 50%?**
  Garantiza niveles en la **zona de flujo** (Flow): no son niveles triviales o automáticos donde basta dejar presionado correr sin peligro (>50%), ni niveles 'kaizo' frustrantes de ensayo-error y trampa milimétrica (<30%). Buscamos desafío disfrutable y justo.

- **¿Por qué exigir ≥ 150 intentos?**
  Por la **Ley de los Grandes Números**: un nivel subido recientemente con 1 intento y 1 victoria arrojaría un 100% artificial de clear rate. Exigir al menos 150 intentos asegura que la tasa de superación sea estadísticamente representativa y confiable.

- **¿Cómo se garantiza que sean completables?**
  Se aplica un **doble filtro de garantía**:
  1. Para subir un nivel a Nintendo Network, el creador humano debe obligatoriamente completarlo de principio a fin (*Clear Check* de Nintendo).
  2. Tras la conversión a formato MAF/ASCII, nuestro agente inteligente **A* (Robin Baumgarten)** simula la partida y recorre físicamente el nivel hasta tocar la meta. Si el agente A* no lo supera en tiempo límite, el nivel se descarta.

