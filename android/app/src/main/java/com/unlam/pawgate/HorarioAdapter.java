package com.unlam.pawgate;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter para el RecyclerView de Horarios programados.
 *
 * Cada item es una card con:
 *   - hora de inicio (07:30)
 *   - rango (- 09:00)
 *   - tipo (Abrir automatico / Modo nocturno / Paseo tarde)
 *   - lista de chips de dias (L M X J V S D)
 *   - badge ON/OFF segun si el horario esta activo
 *
 * Los chips de dias se generan dinamicamente en onBind (no en XML porque
 * cada horario tiene un set de dias distinto).
 */
public class HorarioAdapter extends RecyclerView.Adapter<HorarioAdapter.HorarioVH> {

    public static final class Horario {
        public final String inicio;
        public final String fin;
        public final int[] diasRes;
        public final int tipoRes;
        public final boolean activo;

        public Horario(String inicio, String fin, int[] diasRes, int tipoRes, boolean activo) {
            this.inicio = inicio;
            this.fin = fin;
            this.diasRes = diasRes;
            this.tipoRes = tipoRes;
            this.activo = activo;
        }
    }

    private final List<Horario> data;

    public HorarioAdapter(List<Horario> data) {
        this.data = data;
    }

    @NonNull
    @Override
    public HorarioVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_horario, parent, false);
        return new HorarioVH(row);
    }

    @Override
    public void onBindViewHolder(@NonNull HorarioVH holder, int position) {
        holder.bind(data.get(position));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static final class HorarioVH extends RecyclerView.ViewHolder {
        private final TextView time;
        private final TextView range;
        private final TextView tipo;
        private final LinearLayout days;
        private final TextView badge;

        HorarioVH(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.horario_time);
            range = itemView.findViewById(R.id.horario_range);
            tipo = itemView.findViewById(R.id.horario_tipo);
            days = itemView.findViewById(R.id.horario_days);
            badge = itemView.findViewById(R.id.horario_badge);
        }

        void bind(Horario h) {
            time.setText(h.inicio);
            range.setText(h.fin);
            tipo.setText(h.tipoRes);

            renderDays(h.diasRes);
            renderBadge(h.activo);
        }

        private void renderDays(int[] diasRes) {
            // Limpia chips del binding anterior (ViewHolder se recicla).
            days.removeAllViews();
            for (int diaRes : diasRes) {
                days.addView(makeChip(days.getContext().getString(diaRes)));
            }
        }

        private TextView makeChip(String letra) {
            TextView chip = new TextView(itemView.getContext());
            int dp4 = dp(4), dp6 = dp(6), dp2 = dp(2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp4);
            chip.setLayoutParams(lp);
            chip.setText(letra);
            chip.setTextSize(11);
            chip.setTypeface(chip.getTypeface(), Typeface.BOLD);
            chip.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.text_secondary));
            chip.setBackgroundResource(R.drawable.bg_day_chip);
            chip.setPadding(dp6, dp2, dp6, dp2);
            return chip;
        }

        private void renderBadge(boolean activo) {
            badge.setText(activo ? R.string.horarios_estado_on : R.string.horarios_estado_off);
            badge.setBackgroundResource(activo ? R.drawable.bg_badge_on : R.drawable.bg_badge_off);
            badge.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    activo ? R.color.bg_card : R.color.text_muted));
            int dp8 = dp(8), dp3 = dp(3);
            badge.setPadding(dp8, dp3, dp8, dp3);
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }
    }
}
