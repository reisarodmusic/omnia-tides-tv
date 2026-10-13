package com.omniatides.madamelua;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
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

        web.setWebViewClient(new WebViewClient() {
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
                try {
                    startActivityForResult(parametros.createIntent(), PETICION_ARCHIVO);
                } catch (ActivityNotFoundException e) {
                    selectorArchivos = null;
                    llamada.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, R.string.sin_exploradores, Toast.LENGTH_LONG).show();
                }
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

    @Override
    protected void onActivityResult(int codigo, int resultado, Intent datos) {
        if (codigo == PETICION_ARCHIVO) {
            if (selectorArchivos != null) {
                selectorArchivos.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultado, datos));
                selectorArchivos = null;
            }
            return;
        }
        super.onActivityResult(codigo, resultado, datos);
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
