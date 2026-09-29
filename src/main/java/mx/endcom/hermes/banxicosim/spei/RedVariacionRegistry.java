package mx.endcom.hermes.banxicosim.spei;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mx.endcom.hermes.banxicosim.config.SimConfig;

/**
 * Spec 005 (capa A), decisión "Turno único y sin autenticación": una sola variación de red activa
 * a la vez, controlada en memoria por la API -- reemplaza el contenedor marcador
 * {@code spei-fault-lock} que usaba el puente por SSH (skill {@code spei-network-fault-injection}).
 * Construida una vez en {@code Main}, pasada a {@code SpeiServer} (que la reenvía a cada
 * {@code SpeiSession}) y a {@code ControlServer}.
 *
 * <p>El vencimiento automático corre aquí, no en la sesión SPEI -- una variación debe vencer
 * aunque la sesión que la disparó ya haya muerto (ver spec &sect;"Límites por defecto": "Toda
 * variación vence sola").</p>
 */
public final class RedVariacionRegistry {

	private static final Logger logger = LoggerFactory.getLogger(RedVariacionRegistry.class);

	private final SimConfig config;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "red-variacion-vencimiento");
		t.setDaemon(true);
		return t;
	});
	private final AtomicReference<RedVariacion> activaRef = new AtomicReference<>();
	private volatile ScheduledFuture<?> vencimiento;

	// Throttling (capa A): límites vigentes, leídos por los streams throttleados de la sesión SPEI
	// actual. Long.MAX_VALUE = sin límite. Independiente por dirección (spec &sect;3).
	private final ThrottledOutputStream.TokenBucket limiteSalida = new ThrottledOutputStream.TokenBucket(Long.MAX_VALUE);
	private final ThrottledOutputStream.TokenBucket limiteEntrada = new ThrottledOutputStream.TokenBucket(Long.MAX_VALUE);

	public RedVariacionRegistry(SimConfig config) {
		this.config = config;
	}

	ThrottledOutputStream.TokenBucket limiteSalida() {
		return limiteSalida;
	}

	ThrottledOutputStream.TokenBucket limiteEntrada() {
		return limiteEntrada;
	}

	/** La variación activa vigente, o vacío si no hay ninguna (nunca devuelve una ya terminada --
	 *  ver {@link #limpiarSiEsLaActiva}). */
	public Optional<RedVariacion> activa() {
		RedVariacion v = activaRef.get();
		return (v != null && v.estado() == RedVariacion.Estado.ACTIVA) ? Optional.of(v) : Optional.empty();
	}

	/** {@code POST /red/variacion} -- arranca {@code nueva} si no hay ninguna activa. Vacío en
	 *  conflicto -- quien llama arma el 409 consultando {@link #activa()} para el detalle. */
	public synchronized Optional<RedVariacion> iniciar(RedVariacion nueva) {
		if (activa().isPresent()) {
			return Optional.empty();
		}
		activaRef.set(nueva);
		if (nueva.tipo() == RedVariacion.Tipo.THROTTLING) {
			limiteSalida.setBytesPorSegundo(nueva.bytesPorSegundoOut());
			limiteEntrada.setBytesPorSegundo(nueva.bytesPorSegundoIn());
		}
		vencimiento = scheduler.schedule(() -> vencer(nueva), nueva.duracionSegundos(), TimeUnit.SECONDS);
		logger.info("[RedVariacion] Armada {} tipo={} quien={} duracionSegundos={}",
				nueva.id(), nueva.tipo(), nueva.quien(), nueva.duracionSegundos());
		return Optional.of(nueva);
	}

	private synchronized void vencer(RedVariacion v) {
		v.finish(RedVariacion.Estado.VENCIDA, "Venció tras " + v.duracionSegundos() + "s sin detenerse manualmente");
		limpiarSiEsLaActiva(v);
	}

	/** {@code POST /red/variacion/detener} -- para manualmente la activa, si la hay. */
	public synchronized Optional<RedVariacion> detener() {
		RedVariacion actual = activa().orElse(null);
		if (actual == null) {
			return Optional.empty();
		}
		actual.finish(RedVariacion.Estado.DETENIDA, "Detenida manualmente");
		limpiarSiEsLaActiva(actual);
		return Optional.of(actual);
	}

	/** Llamado por {@link RedVariacionControl} cuando una variación de disparo único (retraso o
	 *  duplicación con ocurrencia exacta, o cualquier corte) termina al coincidir. */
	synchronized void marcarTerminada(RedVariacion v, String detalle) {
		v.finish(RedVariacion.Estado.TERMINADA, detalle);
		logger.info("[RedVariacion] {} terminada: {}", v.id(), detalle);
		limpiarSiEsLaActiva(v);
	}

	private void limpiarSiEsLaActiva(RedVariacion v) {
		activaRef.compareAndSet(v, null);
		if (v.tipo() == RedVariacion.Tipo.THROTTLING) {
			limiteSalida.setBytesPorSegundo(Long.MAX_VALUE);
			limiteEntrada.setBytesPorSegundo(Long.MAX_VALUE);
		}
		if (vencimiento != null) {
			vencimiento.cancel(false);
		}
	}

	/** {@code GET /capacidades} -- capa A siempre disponible; capa B (script del host) queda como
	 *  punto de extensión (spec 005 &sect;1), todavía no implementada. */
	public Map<String, Object> capacidades() {
		Map<String, Object> m = new LinkedHashMap<>();

		Map<String, Object> capaA = new LinkedHashMap<>();
		capaA.put("disponible", true);
		capaA.put("tipos", List.of("retraso", "corte", "duplicacion", "throttling"));
		capaA.put("puntos", List.of("Greeting", "SmLoginReq", "EnSesion", "ClvSim", "MsjCatalogos",
				"AreYouAlive", "FinReenvio", "AcuseRecibo", "Abonos"));
		Map<String, Object> limites = new LinkedHashMap<>();
		limites.put("retrasoMaximoMs", config.redVariacionRetrasoMaximoMs());
		limites.put("duplicacionProbabilidadMaxima", config.redVariacionDuplicacionProbabilidadMaxima());
		limites.put("duracionSegundosDefault", config.redVariacionDuracionSegundosDefault());
		limites.put("duracionSegundosMaxima", config.redVariacionDuracionSegundosMaxima());
		capaA.put("limites", limites);
		m.put("capaA", capaA);

		Map<String, Object> capaB = new LinkedHashMap<>();
		capaB.put("disponible", false); // punto de extensión, spec 005 &sect;1 -- no implementada aún
		m.put("capaB", capaB);

		activa().ifPresentOrElse(v -> m.put("variacionActiva", v.status()),
				() -> m.put("variacionActiva", null));
		return m;
	}
}
