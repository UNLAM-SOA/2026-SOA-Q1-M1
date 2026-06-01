package com.unlam.pawgate;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

/**
 * Historial - lista de eventos de la puerta.
 *
 * Implementacion con RecyclerView + Adapter + ViewHolder (patron clasico Android).
 *   - data: lista hardcodeada de eventos (en Fase 17 vendra del backend).
 *   - HistorialAdapter: convierte cada Evento en una row del item_historial_event.xml
 *   - DividerItemDecoration: linea fina entre items (sin necesidad de inflar Views)
 *
 * Los chips de filtro (Todas/Hoy/Ayer/7d) por ahora son solo UI (no filtran).
 * Cuando conectemos al backend, el chip seleccionado va a triggear una request
 * con el rango temporal correspondiente.
 */
public class HistorialActivity extends AppCompatActivity {

    private TextView chipTodas;
    private TextView chipHoy;
    private TextView chipAyer;
    private TextView chip7d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_historial);

        findViewById(R.id.historial_back).setOnClickListener(v -> finish());
        findViewById(R.id.historial_filter).setOnClickListener(
                v -> showToast(R.string.toast_coming_soon));

        wireChips();

        // 1) Modelo: lista de eventos a mostrar
        List<HistorialAdapter.Evento> data = Arrays.asList(
                new HistorialAdapter.Evento(R.drawable.ic_door_open, R.string.historial_event_puerta_abierta, "hace 2m"),
                new HistorialAdapter.Evento(R.drawable.ic_lightbulb_off, R.string.historial_event_luz_apagada, "hace 18m"),
                new HistorialAdapter.Evento(R.drawable.ic_lock, R.string.historial_event_puerta_bloqueada, "hace 1h"),
                new HistorialAdapter.Evento(R.drawable.ic_lightbulb, R.string.historial_event_luz_encendida, "ayer · 22:16"),
                new HistorialAdapter.Evento(R.drawable.ic_wifi, R.string.historial_event_wifi, "ayer · 21:14"),
                new HistorialAdapter.Evento(R.drawable.ic_calendar, R.string.historial_event_nocturno, "ayer · 20:00"),
                new HistorialAdapter.Evento(R.drawable.ic_lock_open, R.string.historial_event_puerta_desbloqueada, "ayer · 18:42")
        );

        // 2) RecyclerView: LayoutManager + Adapter + ItemDecoration (divider)
        RecyclerView list = findViewById(R.id.event_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new HistorialAdapter(data));
        list.addItemDecoration(new InsetDividerDecoration(this));

        BottomNavHelper.bind(this, R.id.nav_historial);
    }

    private void wireChips() {
        chipTodas = findViewById(R.id.chip_todas);
        chipHoy = findViewById(R.id.chip_hoy);
        chipAyer = findViewById(R.id.chip_ayer);
        chip7d = findViewById(R.id.chip_7d);
        TextView[] chips = {chipTodas, chipHoy, chipAyer, chip7d};
        for (TextView chip : chips) {
            chip.setOnClickListener(v -> seleccionarChip((TextView) v));
        }
    }

    private void seleccionarChip(TextView active) {
        TextView[] chips = {chipTodas, chipHoy, chipAyer, chip7d};
        for (TextView chip : chips) {
            boolean isActive = chip == active;
            chip.setBackgroundResource(isActive
                    ? R.drawable.bg_filter_chip_active : R.drawable.bg_button_secondary);
            chip.setTextColor(ContextCompat.getColor(this,
                    isActive ? R.color.text_primary : R.color.text_secondary));
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        }
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * ItemDecoration que dibuja una linea de 1dp entre items (no antes del primero
     * ni despues del ultimo). Equivalente a los <View height=1dp/> que metiamos
     * a mano entre rows con addView, pero implementado al estilo RecyclerView.
     */
    static final class InsetDividerDecoration extends RecyclerView.ItemDecoration {
        private final android.graphics.Paint paint;
        private final int heightPx;

        InsetDividerDecoration(@NonNull android.content.Context ctx) {
            paint = new android.graphics.Paint();
            paint.setColor(ContextCompat.getColor(ctx, R.color.border_subtle));
            heightPx = Math.round(ctx.getResources().getDisplayMetrics().density);
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position > 0) outRect.top = heightPx;
        }

        @Override
        public void onDraw(@NonNull android.graphics.Canvas c,
                           @NonNull RecyclerView parent,
                           @NonNull RecyclerView.State state) {
            int left = parent.getPaddingLeft();
            int right = parent.getWidth() - parent.getPaddingRight();
            for (int i = 1; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                int top = child.getTop();
                c.drawRect(left, top - heightPx, right, top, paint);
            }
        }
    }
}
