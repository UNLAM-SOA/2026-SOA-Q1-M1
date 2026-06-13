package com.unlam.pawgate;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

/**
 * Notificaciones - lista de eventos recibidos.
 *
 * Refactorizada a RecyclerView + Adapter + ViewHolder.
 * Los chips Todas/No leidas son visuales por ahora (no filtran).
 */
public class NotificacionesActivity extends AppCompatActivity {

    private TextView chipAll;
    private TextView chipUnread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notificaciones);

        findViewById(R.id.notificaciones_mark_all).setOnClickListener(
                v -> showToast(R.string.toast_coming_soon));

        wireChips();

        List<NotificacionAdapter.Notificacion> data = Arrays.asList(
                new NotificacionAdapter.Notificacion(R.drawable.ic_door_open, R.string.notif_puerta_abierta, "hace 2m", true),
                new NotificacionAdapter.Notificacion(R.drawable.ic_battery_low, R.string.notif_bateria_baja, "hace 18m", true),
                new NotificacionAdapter.Notificacion(R.drawable.ic_lock, R.string.notif_puerta_bloqueada, "hace 1h", false),
                new NotificacionAdapter.Notificacion(R.drawable.ic_phone_call, R.string.notif_llamada_perdida, "hace 3h", false),
                new NotificacionAdapter.Notificacion(R.drawable.ic_wifi, R.string.notif_wifi, "ayer · 21:14", false),
                new NotificacionAdapter.Notificacion(R.drawable.ic_moon, R.string.notif_nocturno, "ayer · 20:00", false),
                new NotificacionAdapter.Notificacion(R.drawable.ic_log_in, R.string.notif_entro, "ayer · 18:42", false)
        );

        RecyclerView list = findViewById(R.id.notif_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new NotificacionAdapter(data));
        list.addItemDecoration(new HistorialActivity.InsetDividerDecoration(this));

        BottomNavHelper.bind(this, R.id.nav_inicio);
    }

    private void wireChips() {
        chipAll = findViewById(R.id.chip_all);
        chipUnread = findViewById(R.id.chip_unread);
        TextView[] chips = {chipAll, chipUnread};
        for (TextView chip : chips) {
            chip.setOnClickListener(v -> seleccionarChip((TextView) v));
        }
    }

    private void seleccionarChip(TextView active) {
        TextView[] chips = {chipAll, chipUnread};
        for (TextView chip : chips) {
            boolean isActive = chip == active;
            chip.setBackgroundResource(isActive ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chip.setTextColor(ContextCompat.getColor(this,
                    isActive ? R.color.bg_card : R.color.text_secondary));
            chip.setPadding(
                    UserInterfaceHelper.dp(this,14),
                    UserInterfaceHelper.dp(this,8),
                    UserInterfaceHelper.dp(this, 14),
                    UserInterfaceHelper.dp(this, 8)
            );
        }
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
