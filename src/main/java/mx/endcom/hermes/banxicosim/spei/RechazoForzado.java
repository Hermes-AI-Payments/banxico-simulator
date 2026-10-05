package mx.endcom.hermes.banxicosim.spei;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import mx.endcom.hermes.banxicosim.validation.MotivoRechazo;

/**
 * Spec 014 -- rechazo forzado de una orden de `OrdenTopoV` (requisito explícito de la spec
 * funcional de la bóveda: "con la posibilidad de forzar un rechazo a propósito para probar cómo
 * reacciona minos"). Independiente de {@link RedVariacionRegistry} (spec 005) a propósito: es una
 * falla de contenido/negocio, no de red -- mezclarlas en el mismo registro las acopla sin
 * necesidad.
 *
 * <p>Turno único (como spec 005), pero sin vencimiento por duración: no es continuo, se consume
 * en la primera orden que coincida y pasa a {@link Estado#TERMINADA}. Si nadie manda una orden que
 * coincida, se cancela manualmente o queda armado indefinidamente.</p>
 */
public final class RechazoForzado {

	public enum Estado {
		ACTIVA, DETENIDA, TERMINADA
	}

	private final String id;
	private final String quien;
	private final MotivoRechazo motivo;
	private final String trackingKey; // null = la próxima orden, de cualquier clave
	private final Instant armadaEn = Instant.now();

	private volatile Estado estado = Estado.ACTIVA;
	private volatile String detalleFinalizacion;
	private volatile Instant fin;

	public RechazoForzado(String id, String quien, MotivoRechazo motivo, String trackingKey) {
		this.id = id;
		this.quien = quien;
		this.motivo = motivo;
		this.trackingKey = trackingKey;
	}

	public String id() {
		return id;
	}

	public String quien() {
		return quien;
	}

	public MotivoRechazo motivo() {
		return motivo;
	}

	public String trackingKey() {
		return trackingKey;
	}

	public Instant armadaEn() {
		return armadaEn;
	}

	public Estado estado() {
		return estado;
	}

	/** {@code true} si esta orden es la que este rechazo forzado debe cubrir: cualquier clave si
	 *  no se especificó una, o exactamente esa clave si sí. */
	public boolean coincideCon(String ordenTrackingKey) {
		return trackingKey == null || trackingKey.equals(ordenTrackingKey);
	}

	public synchronized void finish(Estado nuevoEstado, String detalle) {
		if (estado != Estado.ACTIVA) {
			return;
		}
		estado = nuevoEstado;
		detalleFinalizacion = detalle;
		fin = Instant.now();
	}

	public Map<String, Object> status() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("estado", estado.name());
		m.put("quien", quien);
		m.put("motivo", motivo.codigo());
		m.put("trackingKey", trackingKey);
		m.put("armadaEn", armadaEn.toString());
		m.put("fin", fin == null ? null : fin.toString());
		m.put("detalleFinalizacion", detalleFinalizacion);
		return m;
	}
}
