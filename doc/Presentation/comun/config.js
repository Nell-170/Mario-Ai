/* ============================================================
   CONFIGURACIÓN — edita aquí
   ============================================================ */
const CONFIG = {
  // Rutas a tus videos (relativas a este archivo). Vacío = muestra el placeholder.
  // También puedes pulsar «CARGAR VIDEO» en cada recuadro para probar uno al vuelo.
  videos: {
    angry: "",   // ej: "videos/jugador_enojado.mp4"
    bored: "",   // ej: "videos/jugador_aburrido.mp4"
    flow: ""    // ej: "videos/jugador_disfrutando.mp4"
  },
  // Opcional: tu propio sprite para el "jugador" del gráfico (PNG pixelado).
  // Puede ser un texto o un objeto {angry, bored, happy}. Vacío = héroe original en pixel art.
  sprites: { player: "" },
  // Capturas de pantalla para el diagrama de metodología (rutas a imágenes). Vacío = recuadro para cargar.
  shots: {
    hf: "img/ImagenHuggingFacePrincipal.png",
    welcome: "img/02_bienvenida_sesion.png",
    fam: "img/03_familiarizacion_controles.png",
    consent: "img/01_consentimiento_informado.png",
    survey: "",
    quest: "img/04_evaluacion_post_nivel_likert.png",
    rank: "img/05_clasificacion_preferencia_ranking.png",
    free_invite: "img/07_invitacion_juego_voluntario.png",
    free_play: "img/08_modo_juego_libre.png"
  }
};