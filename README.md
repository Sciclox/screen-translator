# 🔮 Screen Translator (Traductor de Pantalla en Tiempo Real)

> **Una aplicación nativa de Android ultra-ligera para traducir tu pantalla bajo demanda sin bloquear tus gestos táctiles.**

Screen Translator te permite capturar cualquier texto que veas en pantalla (por ejemplo, mangas en japonés, diálogos de videojuegos, imágenes o feeds de redes sociales) y traducirlo al instante de forma local y 100% offline. 

Lo más importante: las traducciones se muestran superpuestas usando ventanas especiales que **no interrumpen tus gestos táctiles** (puedes deslizarte por la pantalla, hacer scroll, pellizcar para hacer zoom y navegar libremente mientras la traducción permanece visible).

---

## ✨ Características Principales

*   **📱 Toques Pasantes (Touch Pass-Through Overlay):** Las traducciones se dibujan en una ventana especial con la bandera `FLAG_NOT_TOUCHABLE`, por lo que tus dedos actúan directamente sobre la aplicación de abajo (manga reader, juego, etc.).
*   **文 Botón Flotante Magnético:** Una burbuja flotante pequeña que puedes arrastrar por toda la pantalla y que se "adhiere" suavemente a los bordes de la pantalla.
    *   **Un toque:** Captura la pantalla actual, ejecuta OCR y superpone las traducciones en español.
    *   **Siguiente toque:** Limpia todas las traducciones de la pantalla para reanudar tu lectura normal.
*   **🔍 OCR Local & Traducción Offline (Google ML Kit):**
    *   Reconocimiento de caracteres de alta precisión diseñado específicamente para texto e ideogramas japoneses (vertical y horizontal) y latinos (inglés).
    *   Traducción 100% en el dispositivo (sin enviar datos a servidores externos, protegiendo tu privacidad y sin gastar megas).
*   **🎨 Diseño Premium Dark Mode:** Una interfaz de control moderna basada en Material Design 3 con esquemas de color espaciales oscuros y estados de servicio animados.
*   **🔋 Optimización de Batería Extrema:** A diferencia de otros traductores que graban la pantalla de forma continua gastando batería y CPU, esta app usa captura bajo demanda (solo crea y destruye la pantalla virtual durante 50ms al pulsar el botón flotante).

---

## 🛠️ Stack Tecnológico

*   **Lenguaje:** Kotlin (Nativo de Android)
*   **Framework de UI:** Material Components 3 para Android XML
*   **Procesamiento de Imagen:** Android MediaProjection API + ImageReader virtual framebuffers
*   **Inteligencia Artificial (En el dispositivo):** 
    *   `Google ML Kit Text Recognition Japanese & Latin`
    *   `Google ML Kit On-Device Translation`
*   **Integración Continua:** GitHub Actions (Compilación automatizada del APK en la nube)

---

## 📦 Compilar y Obtener el APK (En 3 Pasos)

No necesitas instalar pesadas herramientas de desarrollo en tu PC como Android Studio o Gradle. Puedes compilar la aplicación directamente en la nube de GitHub de forma totalmente gratuita:

### 1. Inicializa y sube tu código a GitHub
Crea un repositorio vacío en tu cuenta de GitHub (por ejemplo, `screen-translator`). Abre PowerShell en tu computadora y ejecuta:

```powershell
cd C:\Users\Lenovo\OneDrive\Documentos\Proyecto\screen-translator
git init
git add .
git commit -m "feat: initial commit with on-demand screen translator"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/TU_REPOSITORIO.git
git push -u origin main
```
*(Reemplaza `TU_USUARIO/TU_REPOSITORIO` con el enlace de tu repositorio).*

### 2. Deja que GitHub compile el APK
Una vez subido, ve a la pestaña **Actions** en tu repositorio de GitHub. Verás el flujo de trabajo llamado **"Build Android APK"** en ejecución. Tardará aproximadamente 2 o 3 minutos en configurarse y compilar.

### 3. Descarga e Instala
Cuando finalice con éxito (icono verde `check`), haz clic en la ejecución del flujo y desplázate al final de la página a la sección **Artifacts**. Descarga el archivo zip **`screen-translator-apk`**, descomprímelo y transfiere el archivo `app-debug.apk` a tu teléfono Android para instalarlo.

---

## 🚀 Guía de Configuración en el Celular

1.  **Otorga los permisos:** Al abrir la app por primera vez, te guiará para habilitar el permiso de **Mostrar sobre otras aplicaciones** (Overlay) y las **Notificaciones**.
2.  **Selecciona los Idiomas:** Elige el idioma de origen (ej. *Japonés*) y el idioma de destino (ej. *Español*).
3.  **Descarga los modelos:** Pulsa en **"Descargar Modelo Offline"**. Una barra de progreso te indicará cuando esté listo. (Se descarga una sola vez).
4.  **Inicia el traductor:** Pulsa en **"Iniciar Traductor"** y acepta el permiso del sistema para grabar/proyectar la pantalla.
5.  **Prueba la traducción:** Ve a tu manga favorito, toca la burbuja flotante `"文"` y mira cómo las traducciones en español se posicionan exactamente encima del texto en japonés. Desliza la pantalla con tus dedos libremente. Toca de nuevo el botón para limpiar.
