package mx.endcom.hermes.banxicosim.spei;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spec 005 (capa A) -- una instancia por {@link SpeiSession}. Traduce la variación activa en el
 * {@link RedVariacionRegistry} compartido a una {@link Decision} concreta para el punto que está
 * a punto de mandarse, contando ocurrencias por punto dentro de ESTA sesión (los conteos no
 * sobreviven a una reconexión -- una variación armada contra "abono número 5" se refiere siempre
 * a la sesión SPEI viva cuando se armó).
 *
 * <p>Por qué existe como aviso explícito antes de cada envío, en vez de un decorador de bytes
 * puro: ver spec 005 &sect;"Por qué no hay un decorador único de socket". Ver javadoc de
 * {@link RedVariacion} para la semántica exacta de {@code ocurrencia}.</p>
 */
final class RedVariacionControl {

	/** Decisión inmutable devuelta por {@link #beforeWrite} -- sin estado compartido mutable que
	 *  otro hilo pueda leer a destiempo; se consume una sola vez, en el sitio de envío que la
	 *  pidió. {@code retrasoMs}: cuánto dormir ANTES de tomar {@code writeLock}. {@code duplicar}:
	 *  mandar el mismo frame dos veces DENTRO de {@code writeLock}. {@code cortarDespues}: cerrar
	 *  la sesión DESPUÉS de soltar {@code writeLock}. */
	record Decision(long retrasoMs, boolean duplicar, boolean cortarDespues, String detalleCorte) {
		static final Decision NINGUNA = new Decision(0, false, false, null);
	}

	private final RedVariacionRegistry registry;
	private final Map<String, Integer> ocurrenciasPorPunto = new ConcurrentHashMap<>();

	RedVariacionControl(RedVariacionRegistry registry) {
		this.registry = registry;
	}

	/** Se llama justo antes de tomar {@code writeLock}, para cada uno de los 9 puntos nombrados
	 *  (ver spec 005 &sect;"Puntos nombrados"). */
	Decision beforeWrite(String puntoDesnudo) {
		int contador = ocurrenciasPorPunto.merge(puntoDesnudo, 1, Integer::sum);
		RedVariacion activa = registry.activa().orElse(null);
		if (activa == null) {
			return Decision.NINGUNA;
		}
		return switch (activa.tipo()) {
			case RETRASO -> decidirRetraso(activa, puntoDesnudo, contador);
			case DUPLICACION -> decidirDuplicacion(activa, puntoDesnudo, contador);
			case CORTE -> decidirCorte(activa, puntoDesnudo, contador);
			case THROTTLING -> Decision.NINGUNA; // puro a nivel de bytes, ver Throttled*Stream
		};
	}

	private Decision decidirRetraso(RedVariacion v, String punto, int contador) {
		if (!v.punto().equals(punto) || !coincideOcurrencia(v, contador)) {
			return Decision.NINGUNA;
		}
		long jitter = v.jitterMs() > 0 ? ThreadLocalRandom.current().nextLong(v.jitterMs() + 1) : 0;
		return new Decision(v.latenciaMs() + jitter, false, false, null);
	}

	private Decision decidirDuplicacion(RedVariacion v, String punto, int contador) {
		if (!v.punto().equals(punto) || !coincideOcurrencia(v, contador)) {
			return Decision.NINGUNA;
		}
		boolean duplicar = ThreadLocalRandom.current().nextDouble() < v.probabilidad();
		return new Decision(0, duplicar, false, null);
	}

	private Decision decidirCorte(RedVariacion v, String punto, int contador) {
		String postName = "post-" + punto;
		if (!v.punto().equals(postName)) {
			return Decision.NINGUNA;
		}
		if (v.ocurrenciaExacta() != null && contador != v.ocurrenciaExacta()) {
			return Decision.NINGUNA; // todavía no es la ocurrencia pedida
		}
		if (!v.marcarDisparadaSiPrimera()) {
			return Decision.NINGUNA; // ya disparó (o alguien más ganó la carrera entre hilos)
		}
		registry.marcarTerminada(v, "Corte disparado en " + postName + " (ocurrencia " + contador + ")");
		return new Decision(0, false, true, postName);
	}

	/** Ver javadoc de {@link RedVariacion}: ocurrencia exacta dispara una sola vez (y termina la
	 *  variación); "siguiente" coincide con esa y toda ocurrencia futura mientras siga activa. */
	private boolean coincideOcurrencia(RedVariacion v, int contador) {
		if (v.ocurrenciaExacta() != null) {
			boolean coincide = contador == v.ocurrenciaExacta();
			if (coincide && v.marcarDisparadaSiPrimera()) {
				registry.marcarTerminada(v, "Disparada en ocurrencia " + contador);
			}
			return coincide;
		}
		return true; // "siguiente": abierta mientras la variación esté ACTIVA
	}
}
