package mx.endcom.hermes.banxicosim.spei;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spec 014 -- un solo rechazo forzado activo a la vez, igual de simple que {@link RedVariacionRegistry}
 * pero sin vencimiento por duración (ver javadoc de {@link RechazoForzado}). Construido una vez en
 * {@code Main}, pasado a {@code SpeiServer} (que lo reenvía a cada {@code SpeiSession}) y a
 * {@code ControlServer}.
 */
public final class RechazoForzadoRegistry {

	private static final Logger logger = LoggerFactory.getLogger(RechazoForzadoRegistry.class);

	private final AtomicReference<RechazoForzado> activoRef = new AtomicReference<>();

	public Optional<RechazoForzado> activo() {
		RechazoForzado r = activoRef.get();
		return (r != null && r.estado() == RechazoForzado.Estado.ACTIVA) ? Optional.of(r) : Optional.empty();
	}

	/** {@code POST /pagos/rechazo-forzado} -- arma {@code nuevo} si no hay ninguno activo. */
	public synchronized Optional<RechazoForzado> iniciar(RechazoForzado nuevo) {
		if (activo().isPresent()) {
			return Optional.empty();
		}
		activoRef.set(nuevo);
		logger.info("[RechazoForzado] Armado {} motivo={} quien={} trackingKey={}",
				nuevo.id(), nuevo.motivo(), nuevo.quien(), nuevo.trackingKey());
		return Optional.of(nuevo);
	}

	/** {@code POST /pagos/rechazo-forzado/cancelar}. */
	public synchronized Optional<RechazoForzado> detener() {
		RechazoForzado actual = activo().orElse(null);
		if (actual == null) {
			return Optional.empty();
		}
		actual.finish(RechazoForzado.Estado.DETENIDA, "Detenido manualmente");
		activoRef.compareAndSet(actual, null);
		return Optional.of(actual);
	}

	/** Llamado por {@code SpeiSession} cuando una orden coincide y consume el rechazo armado. */
	synchronized void consumir(RechazoForzado r, String detalle) {
		r.finish(RechazoForzado.Estado.TERMINADA, detalle);
		logger.info("[RechazoForzado] {} consumido: {}", r.id(), detalle);
		activoRef.compareAndSet(r, null);
	}
}
