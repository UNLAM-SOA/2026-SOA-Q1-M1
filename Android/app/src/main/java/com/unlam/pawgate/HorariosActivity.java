package com.unlam.pawgate;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

/**
 * Horarios programados - lista de horarios automaticos de la puerta.
 *
 * Refactorizado a RecyclerView + Adapter + ViewHolder.
 * Los horarios son hardcodeados por ahora (en Fase 17 vendran del backend).
 *
 * El separador entre items es un margin gap simulado por ItemDecoration
 * (no una linea, porque cada horario es una card independiente).
 */
public class HorariosActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_horarios);

        findViewById(R.id.horarios_back).setOnClickListener(v -> finish());
        findViewById(R.id.horarios_add).setOnClickListener(
                v -> Toast.makeText(this, getString(R.string.toast_coming_soon), Toast.LENGTH_SHORT).show());
        findViewById(R.id.horarios_add_schedule).setOnClickListener(
                v -> Toast.makeText(this, getString(R.string.toast_coming_soon), Toast.LENGTH_SHORT).show());

        // Modelo: dias de la semana / fin de semana
        int[] semana = {
                R.string.horarios_day_l, R.string.horarios_day_m1, R.string.horarios_day_x,
                R.string.horarios_day_j, R.string.horarios_day_v
        };
        int[] finde = {R.string.horarios_day_s, R.string.horarios_day_d};

        List<HorarioAdapter.Horario> data = Arrays.asList(
                new HorarioAdapter.Horario("07:30", "— 09:00", semana, R.string.horarios_tipo_abrir, true),
                new HorarioAdapter.Horario("12:00", "— 13:30", semana, R.string.horarios_tipo_abrir, true),
                new HorarioAdapter.Horario("17:30", "— 19:00", finde, R.string.horarios_tipo_abrir, false),
                new HorarioAdapter.Horario("22:00", "— 07:00", semana, R.string.horarios_tipo_nocturno, true),
                new HorarioAdapter.Horario("15:00", "— 16:00", finde, R.string.horarios_tipo_paseo, false)
        );

        RecyclerView list = findViewById(R.id.schedule_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new HorarioAdapter(data));
        list.addItemDecoration(new VerticalGapDecoration(dp(10)));

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** ItemDecoration que mete un gap vertical entre cards (las separa visualmente). */
    static final class VerticalGapDecoration extends RecyclerView.ItemDecoration {
        private final int gapPx;

        VerticalGapDecoration(int gapPx) {
            this.gapPx = gapPx;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position > 0) outRect.top = gapPx;
        }
    }
}
