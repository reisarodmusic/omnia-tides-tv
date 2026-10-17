package com.omniatides.madamelua;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Ventana a pantalla completa que muestra omniatides.com.
 * La web se carga en vivo: cuando actualizas el sitio, la app muestra la versión nueva sin reinstalar nada.
 */
public class MainActivity extends Activity {

    private static final int PETICION_ARCHIVO = 4711;

    private FrameLayout raiz;
    private WebView web;
    private View vistaCompleta;                                   // vídeo a pantalla completa
    private WebChromeClient.CustomViewCallback avisoVistaCompleta;
    private ValueCallback<Uri[]> selectorArchivos;
    private String urlInicio;
    private final PuenteDescargas puente = new PuenteDescargas();

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        urlInicio = getString(R.string.app_url);

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF0A1A24);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0A1A24);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(raiz);

        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);                       // localStorage: configuración, listas, canales RSS…
        ajustes.setDatabaseEnabled(true);
        ajustes.setMediaPlaybackRequiresUserGesture(false);       // los vídeos pueden arrancar solos
        ajustes.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);   // vídeos http:// dentro de la página https://
        ajustes.setSupportZoom(false);
        ajustes.setAllowFileAccess(false);
        ajustes.setUserAgentString(ajustes.getUserAgentString() + " OmniaTidesTV/1.0");

        CookieManager galletas = CookieManager.getInstance();
        galletas.setAcceptCookie(true);
        galletas.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(puente, "OmniaAndroid");
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String agente, String disposicion, String mime, long longitud) {
                descargar(url, agente, disposicion, mime);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView vista, String url) {
                vista.evaluateJavascript(JS_DESCARGAS, null);   // engancha las descargas de la página (blob:) a la app
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView vista, WebResourceRequest peticion) {
                Uri u = peticion.getUrl();
                String esquema = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (esquema.equals("http") || esquema.equals("https")) return false;   // se queda dentro de la app
                try {                                                                   // magnet:, intent:… a quien sepa abrirlos
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, u.toString(), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView vista, WebResourceRequest peticion, WebResourceError error) {
                if (peticion.isForMainFrame()) mostrarSinConexion(peticion.getUrl().toString());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View vista, CustomViewCallback aviso) {      // vídeo a pantalla completa
                if (vistaCompleta != null) { aviso.onCustomViewHidden(); return; }
                vistaCompleta = vista; avisoVistaCompleta = aviso;
                raiz.addView(vista, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() { cerrarPantallaCompleta(); }

            @Override
            public boolean onShowFileChooser(WebView vista, ValueCallback<Uri[]> llamada, FileChooserParams parametros) {
                if (selectorArchivos != null) selectorArchivos.onReceiveValue(null);
                selectorArchivos = llamada;
                boolean varios = parametros.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                String[] tipos = parametros.getAcceptTypes();
                boolean soloVideo = false;
                if (tipos != null) for (String t : tipos) if (t != null && t.startsWith("video")) soloVideo = true;
                Intent[] intentos = new Intent[] { intentoSelector(Intent.ACTION_OPEN_DOCUMENT, varios, soloVideo),
                                                   intentoSelector(Intent.ACTION_GET_CONTENT, varios, soloVideo) };
                for (Intent intento : intentos) {
                    try {
                        startActivityForResult(intento, PETICION_ARCHIVO);
                        return true;
                    } catch (ActivityNotFoundException e) { /* se prueba el siguiente */ }
                }
                selectorArchivos = null;
                llamada.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.sin_exploradores, Toast.LENGTH_LONG).show();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest peticion) { peticion.deny(); }   // sin cámara ni micrófono
        });

        if (estado != null) web.restoreState(estado); else web.loadUrl(urlInicio);
    }

    private void cerrarPantallaCompleta() {
        if (vistaCompleta == null) return;
        raiz.removeView(vistaCompleta);
        vistaCompleta = null;
        web.setVisibility(View.VISIBLE);
        if (avisoVistaCompleta != null) avisoVistaCompleta.onCustomViewHidden();
        avisoVistaCompleta = null;
        web.requestFocus();
    }

    // Pantalla de «sin conexión» con reintento automático cada 15 s y botón (se pulsa con OK)
    private void mostrarSinConexion(String url) {
        String html = "<html><body style='background:#0a1a24;color:#e8e2d0;font-family:sans-serif;text-align:center;padding-top:15vh'>"
            + "<h1 style='color:#e6c36a'>Sin conexi&oacute;n</h1>"
            + "<p style='font-size:22px'>No se pudo abrir " + TextUtils.htmlEncode(url) + "</p>"
            + "<p style='font-size:20px'>Revisa la red de la TV. Se reintentar&aacute; solo en unos segundos.</p>"
            + "<button id='b' autofocus style='font-size:26px;padding:14px 34px;margin-top:20px;border-radius:12px;border:2px solid #e6c36a;background:#12303f;color:#e6c36a'>Reintentar ahora</button>"
            + "<script>var u=" + JSONObject.quote(url) + ";document.getElementById('b').onclick=function(){location.href=u};"
            + "document.getElementById('b').focus();setTimeout(function(){location.href=u},15000);</script></body></html>";
        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", url);
    }

    private Intent intentoSelector(String accion, boolean varios, boolean soloVideo) {
        Intent i = new Intent(accion);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (soloVideo) {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "video/*", "application/x-matroska", "application/octet-stream" });
        } else {
            i.setType("*/*");
        }
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, varios);
        return i;
    }

    @Override
    protected void onActivityResult(int codigo, int resultado, Intent datos) {
        if (codigo == PETICION_ARCHIVO) {
            if (selectorArchivos != null) {
                Uri[] elegidos = WebChromeClient.FileChooserParams.parseResult(resultado, datos);
                if (elegidos != null && elegidos.length > 0) {                  // permiso de lectura mientras la app siga abierta
                    for (Uri u : elegidos) {
                        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignorar) { }
                    }
                    Toast.makeText(this, elegidos.length == 1 ? "1 archivo elegido" : elegidos.length + " archivos elegidos", Toast.LENGTH_SHORT).show();
                }
                selectorArchivos.onReceiveValue(elegidos);
                selectorArchivos = null;
            }
            return;
        }
        super.onActivityResult(codigo, resultado, datos);
    }

    // ---- Descargas ---------------------------------------------------------------------------------------------

    private static final String JS_DESCARGAS =
        "(function(){if(window.__omniaDl||!window.OmniaAndroid)return;window.__omniaDl=1;"
        + "function enviar(url,nombre){fetch(url).then(function(r){return r.blob();}).then(function(b){"
        + "OmniaAndroid.empezar(nombre,b.type||'application/octet-stream');var T=3145728,pos=0;"
        + "(function sig(){if(pos>=b.size){OmniaAndroid.terminar();return;}var fr=new FileReader();"
        + "fr.onload=function(){OmniaAndroid.trozo(String(fr.result).split(',')[1]||'');pos+=T;sig();};"
        + "fr.onerror=function(){OmniaAndroid.fallo('lectura');};fr.readAsDataURL(b.slice(pos,pos+T));})();"
        + "}).catch(function(e){OmniaAndroid.fallo(String(e));});}"
        + "window.__omniaGuardar=enviar;"
        + "var oc=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){if(this.hasAttribute('download')&&/^(blob|data):/.test(this.href)){"
        + "enviar(this.href,this.getAttribute('download')||'descarga');return;}return oc.apply(this,arguments);};"
        + "document.addEventListener('click',function(ev){var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;"
        + "if(!a||!a.href||!/^(blob|data):/.test(a.href))return;ev.preventDefault();ev.stopPropagation();"
        + "enviar(a.href,a.getAttribute('download')||'descarga');},true);})();";

    private void aviso(final String texto) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, texto, Toast.LENGTH_LONG).show(); } });
    }

    private void descargar(String url, String agente, String disposicion, String mime) {
        String nombre = URLUtil.guessFileName(url, disposicion, mime);
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            web.evaluateJavascript("window.__omniaGuardar&&window.__omniaGuardar(" + JSONObject.quote(url) + "," + JSONObject.quote(nombre) + ")", null);
            return;
        }
        try {
            DownloadManager.Request pet = new DownloadManager.Request(Uri.parse(url));
            pet.setMimeType(mime);
            pet.addRequestHeader("User-Agent", agente);
            String galletas = CookieManager.getInstance().getCookie(url);
            if (galletas != null) pet.addRequestHeader("Cookie", galletas);
            pet.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            pet.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, nombre);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(pet);
            aviso("Descargando " + nombre + "…");
        } catch (Exception e) {
            aviso("No se pudo descargar: " + e.getMessage());
        }
    }

    /** Recibe desde la página, por trozos, el contenido de un archivo creado en el navegador (blob:) y lo guarda en Descargas. */
    private class PuenteDescargas {
        private OutputStream salida;
        private Uri uriMedia;
        private File archivo;
        private String nombre;

        @JavascriptInterface
        public synchronized void empezar(String nombreArchivo, String mime) {
            cerrarSilencio();
            nombre = nombreArchivo == null ? "descarga" : nombreArchivo.replaceAll("[\\\\/:*?\"<>|]", "_");
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, nombre);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    uriMedia = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uriMedia == null) throw new Exception("sin acceso a Descargas");
                    salida = getContentResolver().openOutputStream(uriMedia);
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    archivo = new File(dir, nombre);
                    salida = new FileOutputStream(archivo);
                }
                aviso("Guardando " + nombre + "…");
            } catch (Exception e) {
                salida = null;
                aviso("No se pudo guardar: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public synchronized void trozo(String base64) {
            if (salida == null) return;
            try { salida.write(Base64.decode(base64, Base64.DEFAULT)); }
            catch (Exception e) { aviso("Error al guardar: " + e.getMessage()); cerrarSilencio(); }
        }

        @JavascriptInterface
        public synchronized void terminar() {
            if (salida == null) return;
            try {
                salida.flush(); salida.close(); salida = null;
                if (uriMedia != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uriMedia, v, null, null);
                    aviso("Guardado en Descargas: " + nombre);
                } else if (archivo != null) {
                    aviso("Guardado: " + archivo.getAbsolutePath());
                }
            } catch (Exception e) {
                aviso("Error al terminar: " + e.getMessage());
            }
            uriMedia = null; archivo = null;
        }

        @JavascriptInterface
        public synchronized void fallo(String motivo) {
            cerrarSilencio();
            aviso("No se pudo preparar la descarga: " + motivo);
        }

        private void cerrarSilencio() {
            try { if (salida != null) salida.close(); } catch (Exception ignorar) { }
            salida = null;
            if (uriMedia != null) { try { getContentResolver().delete(uriMedia, null, null); } catch (Exception ignorar) { } uriMedia = null; }
        }
    }

    @Override
    public boolean onKeyDown(int tecla, KeyEvent evento) {
        if (tecla == KeyEvent.KEYCODE_MENU && vistaCompleta == null) { web.reload(); return true; }   // Menú = recargar
        return super.onKeyDown(tecla, evento);
    }

    @Override
    public void onBackPressed() {
        if (vistaCompleta != null) cerrarPantallaCompleta();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) { super.onSaveInstanceState(estado); web.saveState(estado); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); web.requestFocus(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() { raiz.removeAllViews(); web.destroy(); super.onDestroy(); }

    @Override
    public void onWindowFocusChanged(boolean conFoco) {
        super.onWindowFocusChanged(conFoco);
        if (conFoco) {                                            // pantalla completa sin barras
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
}
