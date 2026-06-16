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
 * Adapter para el RecyclerView del Historial.
 *
 * Patron clasico Android:
 *   - Adapter extends RecyclerView.Adapter<MyViewHolder>
 *   - ViewHolder cachea las refs a las Views del item para evitar findViewById
 *     en cada onBind (que se llama N veces al scrollear).
 *   - El RecyclerView reusa ViewHolders al salir de pantalla (de ahi su nombre):
 *     "recicla" instancias en vez de crear vistas nuevas.
 *
 * Items: una List<Evento> inmutable que se pasa por constructor. Si en el futuro
 * los datos cambian dinamicamente (Fase 17, backend), llamamos a notifyDataSetChanged
 * (o mejor notifyItemInserted / Removed para animaciones gratis).
 */
public class HistorialAdapter extends RecyclerView.Adapter<HistorialAdapter.EventoVH> {

    /**
     * Modelo de un evento del historial. Inmutable a proposito.
     *
     * Hay dos formas de construirlo:
     *   1) Con titulo desde recursos (Evento(icon, R.string.xxx, "subtitulo"))
     *      - usado por los mocks hardcodeados
     *   2) Con titulo como String (Evento(icon, "Texto literal", "subtitulo"))
     *      - usado por HistorialMapper cuando los datos vienen del backend
     *
     * El ViewHolder.bind() decide cual usar segun cual de los dos campos esta seteado.
     */
    public static final class Evento {
        public final int iconRes;
        public final int tituloRes;       // 0 si se usa tituloLiteral
        public final String tituloLiteral; // null si se usa tituloRes
        public final String subtitulo;

        /** Constructor original: titulo desde string resource. */
        public Evento(int iconRes, int tituloRes, String subtitulo) {
            this.iconRes = iconRes;
            this.tituloRes = tituloRes;
            this.tituloLiteral = null;
            this.subtitulo = subtitulo;
        }

        /** Constructor para datos dinamicos del backend: titulo literal. */
        public Evento(int iconRes, String tituloLiteral, String subtitulo) {
            this.iconRes = iconRes;
            this.tituloRes = 0;
            this.tituloLiteral = tituloLiteral;
            this.subtitulo = subtitulo;
        }
    }

    // Mutable para permitir refresh del backend sin recrear el adapter.
    // notifyDataSetChanged es suficiente por ahora; si querramos diffs (animaciones
    // por insert/remove individual), pasaremos a ListAdapter + DiffUtil.
    private final java.util.ArrayList<Evento> data;

    public HistorialAdapter(List<Evento> initialData) {
        this.data = new java.util.ArrayList<>(initialData);
    }

    /** Reemplaza el dataset completo y notifica al RecyclerView. */
    public void setData(List<Evento> newData) {
        data.clear();
        data.addAll(newData);
        notifyDataSetChanged();
    }

    /** Concatena items al final (para infinite scroll / paginacion). */
    public void appendData(List<Evento> moreData) {
        if (moreData == null || moreData.isEmpty()) return;
        int prevSize = data.size();
        data.addAll(moreData);
        notifyItemRangeInserted(prevSize, moreData.size());
    }

    /** Crea un ViewHolder nuevo. Llamado por el RV solo cuando no hay reciclables disponibles. */
    @NonNull
    @Override
    public EventoVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_historial_event, parent, false);
        return new EventoVH(row);
    }

    /** Bindea un ViewHolder a la posicion N. Llamado en cada scroll. */
    @Override
    public void onBindViewHolder(@NonNull EventoVH holder, int position) {
        holder.bind(data.get(position));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /**
     * ViewHolder pattern: cachea las refs a las Views del item.
     * El constructor se llama una sola vez por instancia; el RV reusa instancias
     * al scrollear, asi que findViewById solo corre N veces (no por scroll).
     */
    static final class EventoVH extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView title;
        private final TextView subtitle;

        EventoVH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.event_icon);
            title = itemView.findViewById(R.id.event_title);
            subtitle = itemView.findViewById(R.id.event_subtitle);
        }

        void bind(Evento e) {
            icon.setImageResource(e.iconRes);
            if (e.tituloLiteral != null) {
                title.setText(e.tituloLiteral);
            } else {
                title.setText(e.tituloRes);
            }
            subtitle.setText(e.subtitulo);
        }
    }
}
