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

import com.unlam.pawgate.horarios.Horario;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter de horarios. Cada item es una card con:
 *   - hora_inicio / hora_fin
 *   - nombre
 *   - chips de dias
 *   - badge ON/OFF
 *
 * Tap card -> callback onEditClick (abre form en modo edicion).
 */
public class HorarioAdapter extends RecyclerView.Adapter<HorarioAdapter.HorarioVH> {

    public interface OnHorarioClickListener {
        void onEditClick(Horario h);
    }

    private final List<Horario> data;
    private final OnHorarioClickListener listener;

    public HorarioAdapter(List<Horario> data, OnHorarioClickListener listener) {
        this.data = new ArrayList<>(data);
        this.listener = listener;
    }

    public void setData(List<Horario> newData) {
        data.clear();
        if (newData != null) data.addAll(newData);
        notifyDataSetChanged();
    }

    public int getRealItemCount() {
        return data.size();
    }

    @NonNull
    @Override
    public HorarioVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_horario, parent, false);
        return new HorarioVH(row, listener);
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
        private final TextView tipo; // re-purposed: nombre del horario
        private final LinearLayout days;
        private final TextView badge;
        private final OnHorarioClickListener listener;
        private Horario current;

        private static final int[] DIAS_BITS = {
                Horario.LUN, Horario.MAR, Horario.MIE, Horario.JUE,
                Horario.VIE, Horario.SAB, Horario.DOM
        };
        private static final int[] DIAS_LABELS = {
                R.string.horarios_day_l, R.string.horarios_day_m1, R.string.horarios_day_x,
                R.string.horarios_day_j, R.string.horarios_day_v,
                R.string.horarios_day_s, R.string.horarios_day_d
        };

        HorarioVH(@NonNull View itemView, OnHorarioClickListener listener) {
            super(itemView);
            time = itemView.findViewById(R.id.horario_time);
            range = itemView.findViewById(R.id.horario_range);
            tipo = itemView.findViewById(R.id.horario_tipo);
            days = itemView.findViewById(R.id.horario_days);
            badge = itemView.findViewById(R.id.horario_badge);
            this.listener = listener;
            itemView.setOnClickListener(v -> {
                if (this.listener != null && current != null) this.listener.onEditClick(current);
            });
        }

        void bind(Horario h) {
            this.current = h;
            time.setText(h.formatHoraInicio());
            range.setText("— " + h.formatHoraFin());
            tipo.setText(h.nombre);
            renderDays(h.diasBitmask);
            renderBadge(h.activo);
        }

        private void renderDays(int diasBitmask) {
            days.removeAllViews();
            for (int i = 0; i < DIAS_BITS.length; i++) {
                if ((diasBitmask & DIAS_BITS[i]) != 0) {
                    days.addView(makeChip(days.getContext().getString(DIAS_LABELS[i])));
                }
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
