package com.unlam.pawgate;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class HorariosActivity extends AppCompatActivity {

    private static final class Horario {
        final String inicio;
        final String fin;
        final int[] diasRes;
        final int tipoRes;
        final boolean activo;

        Horario(String inicio, String fin, int[] diasRes, int tipoRes, boolean activo) {
            this.inicio = inicio;
            this.fin = fin;
            this.diasRes = diasRes;
            this.tipoRes = tipoRes;
            this.activo = activo;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_horarios);

        findViewById(R.id.horarios_back).setOnClickListener(v -> finish());
        findViewById(R.id.horarios_add).setOnClickListener(v -> showToast(R.string.toast_coming_soon));
        findViewById(R.id.horarios_add_schedule).setOnClickListener(v -> showToast(R.string.toast_coming_soon));

        int[] semana = {
                R.string.horarios_day_l, R.string.horarios_day_m1, R.string.horarios_day_x,
                R.string.horarios_day_j, R.string.horarios_day_v
        };
        int[] finde = {R.string.horarios_day_s, R.string.horarios_day_d};

        Horario[] horarios = {
                new Horario("07:30", "— 09:00", semana, R.string.horarios_tipo_abrir, true),
                new Horario("12:00", "— 13:30", semana, R.string.horarios_tipo_abrir, true),
                new Horario("17:30", "— 19:00", finde, R.string.horarios_tipo_abrir, false),
                new Horario("22:00", "— 07:00", semana, R.string.horarios_tipo_nocturno, true),
                new Horario("15:00", "— 16:00", finde, R.string.horarios_tipo_paseo, false),
        };

        LinearLayout list = findViewById(R.id.schedule_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Horario h : horarios) {
            list.addView(renderizarHorario(inflater, list, h));
        }

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    private View renderizarHorario(LayoutInflater inflater, ViewGroup parent, Horario h) {
        View card = inflater.inflate(R.layout.item_horario, parent, false);

        ((TextView) card.findViewById(R.id.horario_time)).setText(h.inicio);
        ((TextView) card.findViewById(R.id.horario_range)).setText(h.fin);
        ((TextView) card.findViewById(R.id.horario_tipo)).setText(h.tipoRes);

        LinearLayout days = card.findViewById(R.id.horario_days);
        for (int diaRes : h.diasRes) {
            days.addView(crearChipDia(getString(diaRes)));
        }

        configurarBadge(card.findViewById(R.id.horario_badge), h.activo);
        return card;
    }

    private TextView crearChipDia(String letra) {
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(4));
        chip.setLayoutParams(lp);
        chip.setText(letra);
        chip.setTextSize(11);
        chip.setTypeface(chip.getTypeface(), Typeface.BOLD);
        chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        chip.setBackgroundResource(R.drawable.bg_day_chip);
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        return chip;
    }

    private void configurarBadge(TextView badge, boolean activo) {
        badge.setText(activo ? R.string.horarios_estado_on : R.string.horarios_estado_off);
        badge.setBackgroundResource(activo ? R.drawable.bg_badge_on : R.drawable.bg_badge_off);
        badge.setTextColor(ContextCompat.getColor(this, activo ? R.color.bg_card : R.color.text_muted));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
