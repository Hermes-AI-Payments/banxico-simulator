package mx.endcom.hermes.banxicosim.spei;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spec 005 (capa A) -- modelo de una variación de red armada por la API de control. Mismo patrón
 * que {@link LoadCampaign}: constructor privado + factories estáticas por tipo, dos enums
 * ortogonales, {@code finish()} idempotente, {@code status()} para exponer por HTTP/MCP.
 *
 * <p><b>Semántica de {@code ocurrencia}</b> para los puntos que se repiten dentro de una sesión
 * ({@code Abonos}, {@code AreYouAlive}, {@code FinReenvio}, {@code AcuseRecibo}) -- el spec fija
 * el vocabulario ("un entero o 'siguiente'", specs/005-variaciones-de-red.md &sect;"Puntos
 * nombrados") pero no la mecánica exacta de cuántas veces dispara cada modo; esta es la
 * interpretación que concilia ese vocabulario con los criterios de aceptación ("a un abono
 * concreto" vs "a los próximos N"), pendiente de confirmar contra minos real (ver spec
 * &sect;"Orden de commits", paso 3):</p>
 * <ul>
 *   <li>{@code ocurrenciaExacta} fijo -- coincide UNA sola vez, en ese conteo absoluto exacto
 *       (p. ej. "el abono número 5"); al coincidir, la variación pasa a {@link Estado#TERMINADA}.</li>
 *   <li>{@code siguiente=true} -- coincide con la ocurrencia siguiente y con TODAS las que sigan
 *       mientras la variación esté {@link Estado#ACTIVA} (cubre "los próximos N": quien prueba la
 *       deja corriendo el tiempo/cantidad que necesite y la detiene, o la deja vencer).</li>
 * </ul>
 * Para {@link Tipo#CORTE} el disparo siempre es único (cerrar el socket dos veces no tiene
 * sentido) -- termina en la primera coincidencia sin importar el modo de ocurrencia (ver
 * {@link RedVariacionControl}).
 */
public final class RedVariacion {

	public enum Tipo {
		RETRASO, CORTE, DUPLICACION, THROTTLING
	}

	public enum Estado {
		ACTIVA, DETENIDA, TERMINADA, VENCIDA
	}

	private final String id;
	private final Tipo tipo;
	private final String quien;
	private final Instant armadaEn = Instant.now();
	private final long duracionSegundos;

	// RETRASO / CORTE / DUPLICACION
	private final String punto;
	private final Integer ocurrenciaExacta;
	private final boolean siguiente;
	// RETRASO
	private final long latenciaMs;
	private final long jitterMs;
	// DUPLICACION
	private final double probabilidad;
	// THROTTLING
	private final long bytesPorSegundoOut;
	private final long bytesPorSegundoIn;

	private final AtomicBoolean disparada = new AtomicBoolean(false);
	private volatile Estado estado = Estado.ACTIVA;
	private volatile String detalleFinalizacion;
	private volatile Instant fin;

	private RedVariacion(String id, Tipo tipo, String quien, long duracionSegundos, String punto,
			Integer ocurrenciaExacta, boolean siguiente, long latenciaMs, long jitterMs,
			double probabilidad, long bytesPorSegundoOut, long bytesPorSegundoIn) {
		this.id = id;
		this.tipo = tipo;
		this.quien = quien;
		this.duracionSegundos = duracionSegundos;
		this.punto = punto;
		this.ocurrenciaExacta = ocurrenciaExacta;
		this.siguiente = siguiente;
		this.latenciaMs = latenciaMs;
		this.jitterMs = jitterMs;
		this.probabilidad = probabilidad;
		this.bytesPorSegundoOut = bytesPorSegundoOut;
		this.bytesPorSegundoIn = bytesPorSegundoIn;
	}

	public static RedVariacion retraso(String id, String quien, long duracionSegundos, String punto,
			Integer ocurrenciaExacta, boolean siguiente, long latenciaMs, long jitterMs) {
		return new RedVariacion(id, Tipo.RETRASO, quien, duracionSegundos, punto, ocurrenciaExacta,
				siguiente, latenciaMs, jitterMs, 0, 0, 0);
	}

	/** {@code puntoPost} ya trae el prefijo {@code post-} (ej. {@code post-ClvSim}) -- ver
	 *  vocabulario en el spec. */
	public static RedVariacion corte(String id, String quien, long duracionSegundos, String puntoPost,
			Integer ocurrenciaExacta, boolean siguiente) {
		return new RedVariacion(id, Tipo.CORTE, quien, duracionSegundos, puntoPost, ocurrenciaExacta,
				siguiente, 0, 0, 0, 0, 0);
	}

	public static RedVariacion duplicacion(String id, String quien, long duracionSegundos, String punto,
			Integer ocurrenciaExacta, boolean siguiente, double probabilidad) {
		return new RedVariacion(id, Tipo.DUPLICACION, quien, duracionSegundos, punto, ocurrenciaExacta,
				siguiente, 0, 0, probabilidad, 0, 0);
	}

	public static RedVariacion throttling(String id, String quien, long duracionSegundos,
			long bytesPorSegundoOut, long bytesPorSegundoIn) {
		return new RedVariacion(id, Tipo.THROTTLING, quien, duracionSegundos, null, null, false, 0, 0,
				0, bytesPorSegundoOut, bytesPorSegundoIn);
	}

	public String id() {
		return id;
	}

	public Tipo tipo() {
		return tipo;
	}

	public String quien() {
		return quien;
	}

	public Instant armadaEn() {
		return armadaEn;
	}

	public long duracionSegundos() {
		return duracionSegundos;
	}

	public String punto() {
		return punto;
	}

	public Integer ocurrenciaExacta() {
		return ocurrenciaExacta;
	}

	public boolean siguiente() {
		return siguiente;
	}

	public long latenciaMs() {
		return latenciaMs;
	}

	public long jitterMs() {
		return jitterMs;
	}

	public double probabilidad() {
		return probabilidad;
	}

	public long bytesPorSegundoOut() {
		return bytesPorSegundoOut;
	}

	public long bytesPorSegundoIn() {
		return bytesPorSegundoIn;
	}

	public Estado estado() {
		return estado;
	}

	/** Marca esta variación como ya disparada -- solo el primer llamador entre hilos concurrentes
	 *  gana (usado por {@link RedVariacionControl} para ocurrencia exacta y siempre para corte). */
	public boolean marcarDisparadaSiPrimera() {
		return disparada.compareAndSet(false, true);
	}

	/** Idempotente -- solo el primer llamador (vencimiento automático, detención manual, o
	 *  disparo único) tiene efecto; los demás no hacen nada. */
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
		m.put("tipo", tipo.name().toLowerCase(java.util.Locale.ROOT));
		m.put("estado", estado.name());
		m.put("quien", quien);
		m.put("armadaEn", armadaEn.toString());
		m.put("duracionSegundos", duracionSegundos);
		if (punto != null) {
			m.put("punto", punto);
		}
		if (tipo == Tipo.RETRASO || tipo == Tipo.DUPLICACION || tipo == Tipo.CORTE) {
			m.put("ocurrencia", ocurrenciaExacta != null ? ocurrenciaExacta : "siguiente");
		}
		if (tipo == Tipo.RETRASO) {
			m.put("latenciaMs", latenciaMs);
			m.put("jitterMs", jitterMs);
		}
		if (tipo == Tipo.DUPLICACION) {
			m.put("probabilidad", probabilidad);
		}
		if (tipo == Tipo.THROTTLING) {
			m.put("bytesPorSegundoOut", bytesPorSegundoOut);
			m.put("bytesPorSegundoIn", bytesPorSegundoIn);
		}
		m.put("fin", fin == null ? null : fin.toString());
		m.put("detalleFinalizacion", detalleFinalizacion);
		return m;
	}
}
