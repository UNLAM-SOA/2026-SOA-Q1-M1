package com.unlam.pawgate;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter para el RecyclerView de Notificaciones.
 *
 * <p>Modelo {@link Notificacion} con datos ya formateados (iconRes + titulo/subtitulo
 * como String porque vienen del backend, no de strings.xml). El flag {@code noLeida}
 * controla la visibilidad del dot indicador.
 *
 * <p>Soporta tap individual via {@link OnItemClickListener}: la Activity lo usa
 * para marcar la notif como leida.
 */
public class NotificacionAdapter extends RecyclerView.Adapter<NotificacionAdapter.NotifVH> {

    public interface OnItemClickListener {
        void onClick(Notificacion item, int position);
    }

    public static final class Notificacion {
        /** ID del item en DDB. Null si es un item mock/local. */
        @Nullable public final String notifId;
        public final int iconRes;
        public final String titulo;
        public final String subtitulo;
        public final boolean noLeida;

        public Notificacion(@Nullable String notifId, int iconRes,
                            String titulo, String subtitulo, boolean noLeida) {
            this.notifId = notifId;
            this.iconRes = iconRes;
            this.titulo = titulo;
            this.subtitulo = subtitulo;
            this.noLeida = noLeida;
        }
    }

    private final List<Notificacion> data;
    @Nullable private final OnItemClickListener listener;

    public NotificacionAdapter(List<Notificacion> data) {
        this(data, null);
    }

    public NotificacionAdapter(List<Notificacion> data, @Nullable OnItemClickListener listener) {
        this.data = data;
        this.listener = listener;
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
        Notificacion item = data.get(position);
        holder.bind(item);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onClick(data.get(pos), pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /** Marca un item como leido en la posicion dada y refresca. Usado despues
     *  de un tap exitoso sin recargar toda la lista. */
    public void markRead(int position) {
        if (position < 0 || position >= data.size()) return;
        Notificacion old = data.get(position);
        if (!old.noLeida) return;
        data.set(position, new Notificacion(old.notifId, old.iconRes, old.titulo,
                old.subtitulo, false));
        notifyItemChanged(position);
    }

    /** Marca todas como leidas. Usado por "Leer todo". */
    public void markAllRead() {
        boolean changed = false;
        for (int i = 0; i < data.size(); i++) {
            Notificacion n = data.get(i);
            if (n.noLeida) {
                data.set(i, new Notificacion(n.notifId, n.iconRes, n.titulo, n.subtitulo, false));
                changed = true;
            }
        }
        if (changed) notifyDataSetChanged();
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
            title.setText(n.titulo);
            subtitle.setText(n.subtitulo);
        }
    }
}
