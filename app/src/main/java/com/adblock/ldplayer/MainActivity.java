package com.adblock.ldplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1;

    private TextView tvStatus;
    private TextView tvStats;
    private TextView tvList;
    private Button btnToggle;
    private Button btnUpdate;
    private EditText etAllow;
    private volatile boolean updating = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateUi();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        root.addView(text("AdBlock", 28, true));
        root.addView(text("Bloquea dominios de anuncios con una VPN local que solo filtra las consultas DNS.", 14, false));

        btnToggle = new Button(this);
        btnToggle.setOnClickListener(v -> onToggle());
        root.addView(btnToggle, match());

        tvStatus = text("", 14, false);
        tvStatus.setPadding(0, dp(8), 0, 0);
        root.addView(tvStatus);
        tvStats = text("", 16, true);
        tvStats.setPadding(0, dp(8), 0, 0);
        root.addView(tvStats);
        tvList = text("", 14, false);
        tvList.setPadding(0, dp(4), 0, dp(8));
        root.addView(tvList);

        btnUpdate = new Button(this);
        btnUpdate.setText("Actualizar lista de anuncios");
        btnUpdate.setOnClickListener(v -> onUpdateList());
        root.addView(btnUpdate, match());

        TextView t = text("Excepciones (dominios que NO se bloquean, separados por espacio o coma)", 14, true);
        t.setPadding(0, dp(16), 0, 0);
        root.addView(t);
        etAllow = new EditText(this);
        etAllow.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etAllow.setText(prefs().getString("allow", ""));
        root.addView(etAllow);
        Button btnAllow = new Button(this);
        btnAllow.setText("Guardar excepciones");
        btnAllow.setOnClickListener(v -> {
            String s = etAllow.getText().toString();
            prefs().edit().putString("allow", s).apply();
            Blocklist.setAllow(s);
            toast("Excepciones guardadas");
        });
        root.addView(btnAllow, match());

        TextView note = text("Ojo: si una app deja de funcionar, añade su dominio a las excepciones. "
                + "Los anuncios servidos desde el mismo dominio que el contenido (p. ej. YouTube) "
                + "no se pueden bloquear por DNS. Si en Ajustes tienes «DNS privado» en modo estricto, "
                + "las apps se saltan este filtro.", 12, false);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void onToggle() {
        if (BlockerVpnService.running) {
            startService(new Intent(this, BlockerVpnService.class)
                    .setAction(BlockerVpnService.ACTION_STOP));
            return;
        }
        prefs().edit().putString("allow", etAllow.getText().toString()).apply();
        Intent prepare = VpnService.prepare(this); // null si ya hay permiso
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn();
            } else {
                toast("Hace falta aceptar la conexión VPN para filtrar los anuncios");
            }
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, BlockerVpnService.class).setAction(BlockerVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void onUpdateList() {
        if (updating) {
            return;
        }
        updating = true;
        btnUpdate.setEnabled(false);
        btnUpdate.setText("Descargando…");
        new Thread(() -> {
            String msg;
            try {
                int n = ListUpdater.update(getApplicationContext());
                msg = "Lista actualizada: " + n + " dominios";
            } catch (Exception e) {
                msg = "No se pudo actualizar: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                updating = false;
                btnUpdate.setEnabled(true);
                btnUpdate.setText("Actualizar lista de anuncios");
                toast(m);
                updateUi();
            });
        }, "list-update").start();
    }

    private void updateUi() {
        boolean on = BlockerVpnService.running;
        int total = BlockerVpnService.total;
        int blocked = BlockerVpnService.blocked;
        btnToggle.setText(on ? "Detener bloqueo" : "Iniciar bloqueo");
        tvStatus.setText("Estado: " + BlockerVpnService.status);
        tvStats.setText("Consultas: " + total + " · Bloqueadas: " + blocked
                + (total > 0 ? " (" + (blocked * 100 / total) + " %)" : ""));
        int size = Blocklist.size();
        tvList.setText(size > 0 ? "Dominios en la lista: " + size
                : "La lista se carga al iniciar el bloqueo.");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(BlockerVpnService.PREFS, MODE_PRIVATE);
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
