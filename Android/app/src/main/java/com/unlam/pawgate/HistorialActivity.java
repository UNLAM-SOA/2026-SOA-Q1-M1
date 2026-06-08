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

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

import java.util.ArrayList;
import java.util.Collections;
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

    private HistorialAdapter adapter;
    private DeviceRepository deviceRepo;
    private String deviceId;
    private RecyclerView listView;

    // Filtro temporal seleccionado actualmente (null = "todas")
    private Long currentFromMs = null;
    private Long currentToMs = null;
    private boolean includeSensors = false;

    // Paginacion (infinite scroll)
    private String nextCursor = null;
    private boolean isLoading = false;
    /** Cuando el ultimo item visible esta a <= esta distancia del final, pedimos pag. */
    private static final int LOAD_MORE_THRESHOLD = 5;
    /** Counter que invalida callbacks viejos cuando cambia el filtro. Cada
     *  loadHistory() lo incrementa; los callbacks comparan su version local
     *  y descartan la respuesta si difiere (filtro ya cambio). */
    private int loadVersion = 0;
    // Indice del chip activo: 0=todas, 1=hoy, 2=ayer, 3=7d, -1=custom (filtros avanzados).
    private int activeChipIndex = 0;

    // Keys del Bundle (sobreviven rotacion y process death)
    private static final String STATE_FROM_MS = "filter_from_ms";
    private static final String STATE_TO_MS = "filter_to_ms";
    private static final String STATE_CHIP_INDEX = "filter_chip_index";
    private static final String STATE_INCLUDE_SENSORS = "filter_include_sensors";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_historial);

        this.deviceRepo = new DeviceRepository(this);
        this.deviceId = getString(R.string.default_device_id);

        findViewById(R.id.historial_back).setOnClickListener(v -> finish());
        findViewById(R.id.historial_filter).setOnClickListener(v -> openFiltrosAvanzados());

        wireChips();

        // RecyclerView arranca vacio. Lo llenamos en onResume() con la respuesta del backend.
        listView = findViewById(R.id.event_list);
        final LinearLayoutManager lm = new LinearLayoutManager(this);
        listView.setLayoutManager(lm);
        this.adapter = new HistorialAdapter(Collections.emptyList());
        listView.setAdapter(adapter);
        listView.addItemDecoration(new InsetDividerDecoration(this));

        // Infinite scroll: cuando el user se acerca al final, pedimos la
        // proxima pagina (si el backend dio next_cursor en la respuesta previa).
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return; // solo cuando scrollea hacia abajo
                if (isLoading || nextCursor == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                int total = lm.getItemCount();
                if (lastVisible >= total - LOAD_MORE_THRESHOLD) {
                    loadMoreHistory();
                }
            }
        });

        // Restauracion del filtro post rotacion.
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(STATE_FROM_MS)) {
                currentFromMs = savedInstanceState.getLong(STATE_FROM_MS);
            }
            if (savedInstanceState.containsKey(STATE_TO_MS)) {
                currentToMs = savedInstanceState.getLong(STATE_TO_MS);
            }
            includeSensors = savedInstanceState.getBoolean(STATE_INCLUDE_SENSORS, false);
            activeChipIndex = savedInstanceState.getInt(STATE_CHIP_INDEX, 0);
            highlightChipByIndex(activeChipIndex);
        } else {
            highlightChipByIndex(0); // arranca con "Todas" marcado
        }

        BottomNavHelper.bind(this, R.id.nav_historial);
    }

    /** Abre el BottomSheet de filtros avanzados (W13). Al aplicar, refresca la lista. */
    private void openFiltrosAvanzados() {
        HistorialFiltrosBottomSheet.Filtros current =
                new HistorialFiltrosBottomSheet.Filtros(currentFromMs, currentToMs, includeSensors);
        HistorialFiltrosBottomSheet.show(getSupportFragmentManager(), current, filtros -> {
            currentFromMs = filtros.fromMs;
            currentToMs = filtros.toMs;
            includeSensors = filtros.includeSensors;
            if (filtros.hasCustomRange()) {
                // Si el user puso rango custom, ningun chip queda destacado.
                activeChipIndex = -1;
                highlightChipByIndex(-1);
            } else {
                // Filtros limpiados -> volvemos a "Todas".
                activeChipIndex = 0;
                highlightChipByIndex(0);
            }
            loadHistory();
        });
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentFromMs != null) outState.putLong(STATE_FROM_MS, currentFromMs);
        if (currentToMs != null) outState.putLong(STATE_TO_MS, currentToMs);
        outState.putInt(STATE_CHIP_INDEX, activeChipIndex);
        outState.putBoolean(STATE_INCLUDE_SENSORS, includeSensors);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refrescamos cada vez que la activity se vuelve visible. Asi el user ve
        // los eventos nuevos sin tener que cerrar/reabrir la app.
        // (Si queremos refresh "vivo" mientras esta en pantalla, agregamos polling
        // como en ControlActivity. Por ahora un refresh por entrada alcanza.)
        loadHistory();
    }

    // ============================================================
    // BACKEND CALL
    // ============================================================

    /** Primera pagina: invalida callbacks pendientes, resetea cursor, reemplaza data. */
    private void loadHistory() {
        loadVersion++;          // invalida callbacks viejos
        nextCursor = null;
        isLoading = true;
        final int myVersion = loadVersion;
        android.util.Log.d("HistorialActivity",
                "loadHistory v=" + myVersion + " from=" + currentFromMs
                        + " to=" + currentToMs + " sensors=" + includeSensors);
        deviceRepo.history(deviceId, currentFromMs, currentToMs, includeSensors, null,
                new ApiCallback<DeviceDtos.HistoryResponse>() {
            @Override
            public void onSuccess(DeviceDtos.HistoryResponse result) {
                if (myVersion != loadVersion) {
                    android.util.Log.d("HistorialActivity",
                            "loadHistory v=" + myVersion + " stale, ignoring");
                    return;
                }
                isLoading = false;
                List<DeviceDtos.Event> events = result != null && result.events != null
                        ? result.events : new ArrayList<>();
                adapter.setData(HistorialMapper.mapAll(events));
                nextCursor = result != null ? result.next_cursor : null;
                android.util.Log.d("HistorialActivity",
                        "loadHistory v=" + myVersion + " ok, count=" + events.size()
                                + " next_cursor=" + (nextCursor != null ? "yes" : "no"));
            }
            @Override
            public void onError(String message) {
                if (myVersion != loadVersion) return;
                isLoading = false;
                Toast.makeText(HistorialActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Pagina siguiente: usa nextCursor, appendea data al adapter. */
    private void loadMoreHistory() {
        if (isLoading || nextCursor == null) return;
        isLoading = true;
        final String cursor = nextCursor;
        final int myVersion = loadVersion;
        android.util.Log.d("HistorialActivity",
                "loadMore v=" + myVersion + " cursor=" + cursor.substring(0, Math.min(20, cursor.length())) + "...");
        deviceRepo.history(deviceId, currentFromMs, currentToMs, includeSensors, cursor,
                new ApiCallback<DeviceDtos.HistoryResponse>() {
            @Override
            public void onSuccess(DeviceDtos.HistoryResponse result) {
                if (myVersion != loadVersion) {
                    android.util.Log.d("HistorialActivity",
                            "loadMore v=" + myVersion + " stale, ignoring");
                    return;
                }
                isLoading = false;
                if (result == null) { nextCursor = null; return; }
                List<DeviceDtos.Event> events = result.events != null
                        ? result.events : new ArrayList<>();
                adapter.appendData(HistorialMapper.mapAll(events));
                nextCursor = result.next_cursor;
                android.util.Log.d("HistorialActivity",
                        "loadMore v=" + myVersion + " appended=" + events.size()
                                + " next_cursor=" + (nextCursor != null ? "yes" : "no"));
            }
            @Override
            public void onError(String message) {
                if (myVersion != loadVersion) return;
                isLoading = false;
                Toast.makeText(HistorialActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
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
        int index = 0;
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == active) { index = i; break; }
        }
        activeChipIndex = index;
        highlightChipByIndex(index);

        long nowMs = System.currentTimeMillis();
        long dayMs = 24L * 60L * 60L * 1000L;
        if (active == chipHoy) {
            currentFromMs = startOfTodayMs();
            currentToMs = nowMs;
        } else if (active == chipAyer) {
            currentFromMs = startOfTodayMs() - dayMs;
            currentToMs = startOfTodayMs();
        } else if (active == chip7d) {
            currentFromMs = nowMs - 7 * dayMs;
            currentToMs = nowMs;
        } else { // chipTodas
            currentFromMs = null;
            currentToMs = null;
        }
        loadHistory();
    }

    /** Aplica el highlight visual al chip del indice dado. Usado por seleccionarChip
     *  y por el restore post rotacion. */
    private void highlightChipByIndex(int index) {
        TextView[] chips = {chipTodas, chipHoy, chipAyer, chip7d};
        for (int i = 0; i < chips.length; i++) {
            boolean isActive = (i == index);
            chips[i].setBackgroundResource(isActive
                    ? R.drawable.bg_filter_chip_active : R.drawable.bg_button_secondary);
            chips[i].setTextColor(ContextCompat.getColor(this,
                    isActive ? R.color.text_primary : R.color.text_secondary));
            chips[i].setPadding(dp(12), dp(6), dp(12), dp(6));
        }
    }

    /** Epoch ms del 00:00:00 de hoy en la timezone del device. */
    private long startOfTodayMs() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
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
