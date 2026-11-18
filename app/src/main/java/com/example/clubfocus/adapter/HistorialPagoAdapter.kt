package com.example.clubfocus.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.clubfocus.R
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.PagoHistorialItem
import com.example.clubfocus.data.entity.TipoCuota
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class HistorialPagoAdapter(
    private var cuotas: List<PagoHistorialItem>,
    private val abrirComprobante: (PagoHistorialItem) -> Unit
) : RecyclerView.Adapter<HistorialPagoAdapter.PagoViewHolder>() {

    private val formatterFecha = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val formatterMonto = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }

    class PagoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtSocioInfo: TextView = itemView.findViewById(R.id.txtSocioInfo)
        val txtCuota: TextView = itemView.findViewById(R.id.txtCuota)
        val txtVenc: TextView = itemView.findViewById(R.id.txtVenc)
        val txtTipoCuota: TextView = itemView.findViewById(R.id.txtTipoCuota)
        val txtMedioPago: TextView = itemView.findViewById(R.id.txtMedioPago)
        val txtMonto: TextView = itemView.findViewById(R.id.txtMonto)
        val txtEstado: TextView = itemView.findViewById(R.id.txtEstado)
        val txtVerComprobante: TextView = itemView.findViewById(R.id.txtVerComprobanteHistorial)
        val txtAnulacion: TextView = itemView.findViewById(R.id.txtAnulacionHistorial)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_historial_pago, parent, false)
        return PagoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PagoViewHolder, position: Int) {
        val item = cuotas[position]
        val context = holder.itemView.context

        holder.txtSocioInfo.text = "${item.nombreCompleto}  •  DNI: ${item.dni}"

        val fechaPago = item.fechaPago
        val anulacion = item.anulacion
        val tieneComprobante = anulacion == null && item.estado == EstadoCuota.PAGADA &&
            fechaPago != null && item.medioPago != null
        holder.txtVerComprobante.visibility = if (tieneComprobante) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener(
            if (tieneComprobante) View.OnClickListener { abrirComprobante(item) } else null
        )
        holder.itemView.isClickable = tieneComprobante
        holder.itemView.isFocusable = tieneComprobante

        holder.txtCuota.text = when {
            item.tipo == TipoCuota.DIARIA -> "Cuota Diaria"
            fechaPago == null -> "Cuota Mensual"
            else -> {
                val mes = fechaPago.month
                    .getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES"))
                    .replaceFirstChar { it.uppercase() }
                "Cuota $mes ${fechaPago.year}"
            }
        }

        holder.txtVenc.text = item.fechaVencimiento.format(formatterFecha)
        holder.txtTipoCuota.text = if (item.tipo == TipoCuota.MENSUAL) "Socio" else "No Socio"

        holder.txtMedioPago.text = when (item.medioPago) {
            MedioPago.EFECTIVO -> "Efectivo"
            MedioPago.TARJETA_CREDITO -> "Tarjeta de crédito"
            MedioPago.TARJETA_DEBITO -> "Tarjeta de débito"
            null -> "-"
        }

        holder.txtMonto.text = context.getString(R.string.monto_formato, formatterMonto.format(item.montoFinal))

        holder.txtAnulacion.visibility = if (anulacion == null) View.GONE else View.VISIBLE
        holder.txtAnulacion.text = anulacion?.let {
            context.getString(R.string.historial_anulacion_detalle, it.fecha, it.usuario, it.motivo)
        }.orEmpty()
        if (anulacion != null) {
            holder.txtEstado.setText(R.string.historial_pago_anulado)
            holder.txtEstado.setBackgroundResource(R.drawable.label_danger)
            holder.txtEstado.setTextColor(ContextCompat.getColor(context, R.color.badge_vencida_text))
            return
        }
        when (item.estado) {
            EstadoCuota.PAGADA -> {
                holder.txtEstado.text = "Pagada"
                holder.txtEstado.setBackgroundResource(R.drawable.label_success)
                holder.txtEstado.setTextColor(ContextCompat.getColor(context, R.color.badge_pagada_text))
            }
            EstadoCuota.PENDIENTE -> {
                holder.txtEstado.text = "Pendiente"
                holder.txtEstado.setBackgroundResource(R.drawable.label_warning)
                holder.txtEstado.setTextColor(ContextCompat.getColor(context, R.color.badge_pendiente_text))
            }
            EstadoCuota.VENCIDA -> {
                holder.txtEstado.text = "Vencida"
                holder.txtEstado.setBackgroundResource(R.drawable.label_danger)
                holder.txtEstado.setTextColor(ContextCompat.getColor(context, R.color.badge_vencida_text))
            }
        }
    }

    override fun getItemCount(): Int = cuotas.size

    fun actualizarLista(nuevaLista: List<PagoHistorialItem>) {
        cuotas = nuevaLista
        notifyDataSetChanged()
    }
}
