package com.unlam.pawgate;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter para el RecyclerView de Notificaciones.
 *
 * Sigue el mismo patron que HistorialAdapter:
 *   - Modelo Notificacion (POJO inmutable)
 *   - ViewHolder cachea las refs a las Views del item
 *   - onBind aplica el modelo al ViewHolder
 *
 * Diferencia con Historial: cada notificacion tiene un flag noLeida que
 * controla la visibilidad del dot indicador a la izquierda.
 */
public class NotificacionAdapter extends RecyclerView.Adapter<NotificacionAdapter.NotifVH> {

    public static final class Notificacion {
        public final int iconRes;
        public final int tituloRes;
        public final String subtitulo;
        public final boolean noLeida;

        public Notificacion(int iconRes, int tituloRes, String subtitulo, boolean noLeida) {
            this.iconRes = iconRes;
            this.tituloRes = tituloRes;
            this.subtitulo = subtitulo;
            this.noLeida = noLeida;
        }
    }

    private final List<Notificacion> data;

    public NotificacionAdapter(List<Notificacion> data) {
        this.data = data;
    }

    @NonNull
    @Override
    public NotifVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_notificacion, parent, false);
        return new NotifVH(row);
    }

    @Override
    public void onBindViewHolder(@NonNull NotifVH holder, int position) {
        holder.bind(data.get(position));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static final class NotifVH extends RecyclerView.ViewHolder {
        private final View dot;
        private final ImageView icon;
        private final TextView title;
        private final TextView subtitle;

        NotifVH(@NonNull View itemView) {
            super(itemView);
            dot = itemView.findViewById(R.id.notif_dot);
            icon = itemView.findViewById(R.id.notif_icon);
            title = itemView.findViewById(R.id.notif_title);
            subtitle = itemView.findViewById(R.id.notif_subtitle);
        }

        void bind(Notificacion n) {
            // INVISIBLE (no GONE) para preservar el espacio reservado a la izquierda.
            dot.setVisibility(n.noLeida ? View.VISIBLE : View.INVISIBLE);
            icon.setImageResource(n.iconRes);
            title.setText(n.tituloRes);
            subtitle.setText(n.subtitulo);
        }
    }
}
