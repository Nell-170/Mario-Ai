"""
Arma la presentación completa en un solo archivo: presentacion.html

Uso:  python armar.py      (o  python3 armar.py)

Toma, en orden alfabético, cada archivo de la carpeta filminas/ y separa
sus tres partes: <style> va al <head>, la <section> va al escenario y
<script> va al final. Para agregar una filmina nueva, crea por ejemplo
filminas/07-resultados.html con esa misma estructura y vuelve a ejecutar.
"""
import re
from pathlib import Path

RAIZ = Path(__file__).parent
leer = lambda p: (RAIZ / p).read_text(encoding="utf-8")

estilos, secciones, scripts = [], [], []
for f in sorted((RAIZ / "filminas").glob("*.html")):
    t = f.read_text(encoding="utf-8")
    css = re.findall(r"<style>(.*?)</style>", t, re.S)
    js = re.findall(r"<script>(.*?)</script>", t, re.S)
    html = re.sub(r"<style>.*?</style>|<script>.*?</script>", "", t, flags=re.S).strip()
    etiqueta = f"/* ===== {f.name} ===== */"
    estilos.append(etiqueta + "\n" + "\n".join(c.strip("\n") for c in css))
    secciones.append(html)
    scripts.append(etiqueta + "\n" + "\n".join(j.strip("\n") for j in js))

salida = (leer("comun/plantilla.html")
    .replace("/*@@FUENTES@@*/", leer("comun/fuentes.css").strip())
    .replace("/*@@BASE@@*/", leer("comun/base.css").strip())
    .replace("/*@@ESTILOS_FILMINAS@@*/", "\n\n".join(estilos))
    .replace("<!--@@FILMINAS@@-->", "\n\n".join(secciones))
    .replace("/*@@CONFIG@@*/", leer("comun/config.js").strip())
    .replace("/*@@MOTOR@@*/", leer("comun/motor.js").strip())
    .replace("/*@@SCRIPTS_FILMINAS@@*/", "\n\n".join(scripts)))

(RAIZ / "presentacion.html").write_text(salida, encoding="utf-8")
print(f"Listo: presentacion.html armada con {len(secciones)} filminas.")
