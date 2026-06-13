package com.unlam.pawgate;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.ScheduleDtos;
import com.unlam.pawgate.horarios.FormHorarioActivity;
import com.unlam.pawgate.horarios.Horario;
import com.unlam.pawgate.horarios.HorarioMapper;

import java.util.Collections;
import java.util.List;

/**
 * Lista de horarios. Datos desde el backend (DeviceRepository.getSchedules).
 *
 * Tap card -> form en modo edicion (pasamos JSON del horario para evitar
 *             round-trip extra al backend).
 * Tap "+" / "Agregar" -> form en modo nuevo.
 *
 * Refresca en onResume para reflejar cambios hechos en el form.
 */
public class HorariosActivity extends AppCompatActivity {

    private DeviceRepository deviceRepo;
    private String deviceId;
    private HorarioAdapter adapter;

    private View emptyView;
    private View loadingView;
    private RecyclerView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_horarios);

        this.deviceRepo = new DeviceRepository(this);
        this.deviceId = getString(R.string.default_device_id);

        findViewById(R.id.horarios_back).setOnClickListener(v -> finish());
        findViewById(R.id.horarios_add).setOnClickListener(v -> openFormNuevo());
        findViewById(R.id.horarios_add_schedule).setOnClickListener(v -> openFormNuevo());

        listView = findViewById(R.id.schedule_list);
        // Estos views pueden no existir en el layout actual; si no estan,
        // los IDs devuelven null y manejamos defensivo abajo.
        emptyView = findViewById(R.id.horarios_empty);
        loadingView = findViewById(R.id.horarios_loading);

        listView.setLayoutManager(new LinearLayoutManager(this));
        this.adapter = new HorarioAdapter(Collections.emptyList(), this::openFormEditar);
        listView.setAdapter(adapter);
        listView.addItemDecoration(new VerticalGapDecoration(UserInterfaceHelper.dp(this, 10)));

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFromBackend();
    }

    private void loadFromBackend() {
        showLoading();
        deviceRepo.getSchedules(deviceId, new ApiCallback<ScheduleDtos.ListResponse>() {
            @Override
            public void onSuccess(ScheduleDtos.ListResponse result) {
                List<Horario> horarios = HorarioMapper.fromDtos(
                        result != null ? result.schedules : null);
                adapter.setData(horarios);
                showList(horarios.isEmpty());
            }
            @Override
            public void onError(String message) {
                showList(adapter.getRealItemCount() == 0);
                Toast.makeText(HorariosActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ============================================================
    // VIEW STATE HELPERS (loading / empty / list)
    // ============================================================

    private void showLoading() {
        if (loadingView != null) loadingView.setVisibility(View.VISIBLE);
        if (emptyView != null)   emptyView.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);
    }

    private void showList(boolean isEmpty) {
        if (loadingView != null) loadingView.setVisibility(View.GONE);
        if (isEmpty && emptyView != null) {
            emptyView.setVisibility(View.VISIBLE);
            listView.setVisibility(View.GONE);
        } else {
            if (emptyView != null) emptyView.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }
    }

    // ============================================================
    // NAVEGACION
    // ============================================================

    private void openFormNuevo() {
        startActivity(new Intent(this, FormHorarioActivity.class));
    }

    private void openFormEditar(Horario h) {
        Intent i = new Intent(this, FormHorarioActivity.class);
        i.putExtra(FormHorarioActivity.EXTRA_HORARIO_ID, h.id);
        i.putExtra(FormHorarioActivity.EXTRA_HORARIO_JSON, new com.google.gson.Gson().toJson(h));
        startActivity(i);
    }

    static final class VerticalGapDecoration extends RecyclerView.ItemDecoration {
        private final int gapPx;
        VerticalGapDecoration(int gapPx) { this.gapPx = gapPx; }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position > 0) outRect.top = gapPx;
        }
    }
}
