package mx.endcom.hermes.banxicosim.control;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mx.endcom.hermes.banxicosim.config.SimConfig;
import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.LoadCampaign;
import mx.endcom.hermes.banxicosim.spei.RechazoForzado;
import mx.endcom.hermes.banxicosim.spei.RechazoForzadoRegistry;
import mx.endcom.hermes.banxicosim.spei.RedVariacion;
import mx.endcom.hermes.banxicosim.spei.RedVariacionRegistry;
import mx.endcom.hermes.banxicosim.spei.SessionClosure;
import mx.endcom.hermes.banxicosim.spei.SpeiServer;
import mx.endcom.hermes.banxicosim.spei.SpeiSession;
import mx.endcom.hermes.banxicosim.spei.messages.CargosCodec;
import mx.endcom.hermes.banxicosim.validation.MotivoRechazo;

/**
 * API de control HTTP del simulador. No es parte del protocolo SPEI/ARA (minos no le habla a
 * este servidor) -- es una herramienta adicional para disparar y observar corridas de prueba con
 * clientes HTTP simples (ver {@code httpclient/*.http} en la raíz del repo), sin depender de la
 * consola stdin que ya expone {@code Main}. Usa {@link HttpServer} del JDK, incluido desde Java
 * 6, para no agregar una dependencia nueva (ver AGENTS.md &sect;4, "sin frameworks").
 *
 * <p>Endpoints (ver README.md &sect;"API de control y flujo de pruebas" para el contrato
 * completo):</p>
 * <ul>
 *   <li>{@code GET /health} -- liveness del propio simulador.</li>
 *   <li>{@code GET /session} -- estado de la sesión SPEI más reciente (o {@code null}).</li>
 *   <li>{@code POST /abonos/validos} / {@code POST /abonos/invalidos} -- mismo camino que los
 *       comandos de consola {@code abono}/{@code abono-invalido}.</li>
 *   <li>{@code POST /abonos} -- abono de cualquier tipo de pago del catálogo, clave de rastreo y
 *       modo de firma configurables (specs 002/004/006). Ver {@link #triggerCustomAbono}.</li>
 *   <li>{@code POST /heartbeat/detener} -- suspende el {@code AreYouAlive} saliente (spec 009).</li>
 *   <li>{@code POST /abonos/carga} / {@code GET .../{id}} / {@code POST .../{id}/detener} --
 *       campaña de volumen, sostenida o en rampa hasta falla (spec 012). Ver {@link #loadCampaign}.</li>
 *   <li>{@code GET /test-runs} -- corridas de prueba persistidas en H2 (Fase 6).</li>
 *   <li>{@code GET /test-runs/{id}/events} -- eventos de una corrida.</li>
 *   <li>{@code GET /capacidades} -- qué pruebas de red están disponibles y con qué límites
 *       (spec 005, capa A). Ver {@link #capacidades}.</li>
 *   <li>{@code POST /red/variacion} / {@code GET /red/variacion} /
 *       {@code POST /red/variacion/detener} -- variaciones de red (retraso, corte, duplicación,
 *       throttling), una a la vez (spec 005, capa A). Ver {@link #redVariacion}.</li>
 *   <li>{@code POST /pagos/rechazo-forzado} / {@code GET .../} / {@code POST .../cancelar} --
 *       rechazo forzado de la próxima orden de un {@code OrdenTopoV} (spec 014). Ver
 *       {@link #rechazoForzado}.</li>
 *   <li>{@code POST /pagos/cargos} / {@code GET .../pendientes} / {@code POST .../liquidar-lote}
 *       -- {@code Cargos} manual, consulta de lo acumulado, y flush del lote (spec 014, modo
 *       {@code acumulado}). Ver {@link #pagosCargos}.</li>
 *   <li>{@code GET /pagos/saldo} -- saldo del día operativo vigente (spec 014).</li>
 *   <li>{@code POST /dia/cerrar} -- {@code LiquidacionFinal}, cierre de día operativo (spec 014).</li>
 * </ul>
 */
public final class ControlServer {

	private static final Logger logger = LoggerFactory.getLogger(ControlServer.class);
	private static final Pattern RUN_EVENTS_PATH = Pattern.compile("^/test-runs/(\\d+)/events/?$");
	private static final Pattern CAMPAIGN_STOP_PATH = Pattern.compile("^/abonos/carga/([^/]+)/detener/?$");
	private static final Pattern CAMPAIGN_STATUS_PATH = Pattern.compile("^/abonos/carga/([^/]+)/?$");
	private static final Pattern RED_VARIACION_DETENER_PATH = Pattern.compile("^/red/variacion/detener/?$");
	private static final Pattern RECHAZO_FORZADO_CANCELAR_PATH = Pattern.compile("^/pagos/rechazo-forzado/cancelar/?$");
	private static final Pattern CARGOS_PENDIENTES_PATH = Pattern.compile("^/pagos/cargos/pendientes/?$");
	private static final Pattern CARGOS_LIQUIDAR_LOTE_PATH = Pattern.compile("^/pagos/cargos/liquidar-lote/?$");

	private final HttpServer httpServer;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	// Spec 012 -- registro de campañas de carga en curso/terminadas, vivas mientras el proceso
	// siga arriba (no persistidas en H2 -- son corridas efímeras de prueba, no eventos de protocolo).
	private final Map<String, LoadCampaign> loadCampaigns = new ConcurrentHashMap<>();
	private final AtomicLong campaignSequence = new AtomicLong();
	// Spec 005 (capa A) -- variaciones de red.
	private final RedVariacionRegistry redVariacionRegistry;
	private final SimConfig config;
	private final AtomicLong redVariacionSequence = new AtomicLong();
	// Spec 014 -- recepción y liquidación de pagos.
	private final RechazoForzadoRegistry rechazoForzadoRegistry;
	private final H2Store store;
	private final AtomicLong rechazoForzadoSequence = new AtomicLong();

	public ControlServer(int port, SpeiServer speiServer, H2Store store,
			RedVariacionRegistry redVariacionRegistry, RechazoForzadoRegistry rechazoForzadoRegistry,
			SimConfig config) throws IOException {
		this.redVariacionRegistry = redVariacionRegistry;
		this.rechazoForzadoRegistry = rechazoForzadoRegistry;
		this.store = store;
		this.config = config;
		this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
		httpServer.createContext("/health", exchange -> dispatch(exchange, this::health));
		httpServer.createContext("/session", exchange -> dispatch(exchange, ex -> session(ex, speiServer)));
		httpServer.createContext("/abonos/validos",
				exchange -> dispatch(exchange, ex -> triggerAbono(ex, speiServer, true)));
		httpServer.createContext("/abonos/invalidos",
				exchange -> dispatch(exchange, ex -> triggerAbono(ex, speiServer, false)));
		httpServer.createContext("/abonos/carga", exchange -> dispatch(exchange, ex -> loadCampaign(ex, speiServer)));
		httpServer.createContext("/abonos", exchange -> dispatch(exchange, ex -> triggerCustomAbono(ex, speiServer)));
		httpServer.createContext("/heartbeat/detener",
				exchange -> dispatch(exchange, ex -> stopHeartbeat(ex, speiServer)));
		httpServer.createContext("/test-runs", exchange -> dispatch(exchange, ex -> testRuns(ex, store)));
		httpServer.createContext("/capacidades", exchange -> dispatch(exchange, this::capacidades));
		httpServer.createContext("/red/variacion", exchange -> dispatch(exchange, this::redVariacion));
		httpServer.createContext("/pagos/rechazo-forzado", exchange -> dispatch(exchange, this::rechazoForzado));
		httpServer.createContext("/pagos/cargos", exchange -> dispatch(exchange, ex -> pagosCargos(ex, speiServer)));
		httpServer.createContext("/pagos/saldo", exchange -> dispatch(exchange, ex -> saldo(ex, speiServer)));
		httpServer.createContext("/dia/cerrar", exchange -> dispatch(exchange, ex -> cerrarDia(ex, speiServer)));
		httpServer.setExecutor(executor);
	}

	public void start() {
		httpServer.start();
		logger.info("[Control] API de control HTTP escuchando en el puerto {}", httpServer.getAddress().getPort());
	}

	public void stop() {
		httpServer.stop(0);
		executor.shutdownNow();
	}

	// ---- Endpoints ----

	private Response health(HttpExchange exchange) {
		if (!"GET".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		return Response.ok(Map.of("status", "ok"));
	}

	private Response session(HttpExchange exchange, SpeiServer speiServer) {
		if (!"GET".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		SpeiSession session = speiServer.lastSession();
		if (session == null) {
			return Response.ok(sessionMap(null));
		}
		return Response.ok(sessionMap(session));
	}

	private Map<String, Object> sessionMap(SpeiSession session) {
		Map<String, Object> body = new LinkedHashMap<>();
		if (session == null) {
			body.put("session", null);
			return body;
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", session.runId());
		details.put("channel", "SPEI");
		details.put("remoteAddress", session.remoteAddress());
		details.put("alive", session.isAlive());
		details.put("fase", session.phase().name());
		details.put("diaOperativo", session.operationalDate() == null ? null : session.operationalDate().toString());
		details.put("vivaDesde", session.aliveSince() == null ? null : session.aliveSince().toString());
		SessionClosure closure = session.closure();
		if (closure != null) {
			// Spec 013: por qué y cuándo terminó -- antes una sesión TERMINADA no lo decía.
			Map<String, Object> cierre = new LinkedHashMap<>();
			cierre.put("at", closure.at().toString());
			cierre.put("causa", closure.cause().code());
			cierre.put("detalle", closure.detail());
			details.put("cierre", cierre);
		}
		body.put("session", details);
		return body;
	}

	private Response triggerAbono(HttpExchange exchange, SpeiServer speiServer, boolean valid) {
		if (!"POST".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		session.sendTestAbono(valid);
		return Response.ok(Map.of(
				"status", "enviado",
				"valido", valid,
				"runId", session.runId()));
	}

	/**
	 * {@code POST /abonos} -- abono de cualquier tipo de pago del catálogo, con clave de rastreo y
	 * modo de firma configurables (specs 002/004/006). Cuerpo esperado:
	 * {@code {"tipoPg": N, "trackingKey": "opcional", "firma": "valida"|"vacia"|"corrupta",
	 * "campos": {"nombreCampo": "valor", ...}}}. {@code tipoPg} y {@code campos} son obligatorios;
	 * los demás tienen default ({@code trackingKey} autogenerada, {@code firma} "valida").
	 */
	private Response triggerCustomAbono(HttpExchange exchange, SpeiServer speiServer) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request;
		try {
			request = JsonReader.readObject(rawBody);
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "json-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		Object tipoPgRaw = request.get("tipoPg");
		if (!(tipoPgRaw instanceof Number)) {
			return Response.of(400, Map.of("error", "falta-tipoPg", "detalle", "'tipoPg' es obligatorio y debe ser numérico."));
		}
		int tipoPg = ((Number) tipoPgRaw).intValue();

		Object camposRaw = request.get("campos");
		if (!(camposRaw instanceof Map<?, ?> camposMap)) {
			return Response.of(400, Map.of("error", "faltan-campos",
					"detalle", "'campos' es obligatorio: mapa de nombre de campo -> valor según PaymentType."));
		}
		java.util.Map<String, String> campos = new java.util.LinkedHashMap<>();
		for (var entry : camposMap.entrySet()) {
			campos.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
		}

		String trackingKey = request.get("trackingKey") instanceof String s ? s : null;

		String firmaRaw = request.get("firma") instanceof String s ? s.toUpperCase(java.util.Locale.ROOT) : "VALIDA";
		mx.endcom.hermes.banxicosim.spei.messages.AbonosCodec.SignatureMode signatureMode;
		try {
			signatureMode = mx.endcom.hermes.banxicosim.spei.messages.AbonosCodec.SignatureMode.valueOf(firmaRaw);
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "firma-invalida",
					"detalle", "'firma' debe ser uno de: valida, vacia, corrupta."));
		}

		try {
			session.sendCustomAbono(tipoPg, trackingKey, campos, signatureMode);
		} catch (IllegalArgumentException e) {
			return Response.of(422, Map.of("error", "abono-invalido", "detalle", String.valueOf(e.getMessage())));
		} catch (Exception e) {
			logger.error("[Control] Error mandando abono personalizado: {}", e.getMessage(), e);
			return Response.of(500, Map.of("error", "error-interno", "detalle", String.valueOf(e.getMessage())));
		}
		return Response.ok(Map.of("status", "enviado", "tipoPg", tipoPg, "firma", signatureMode.name(),
				"runId", session.runId()));
	}

	/**
	 * Spec 012 -- enruta las tres rutas bajo {@code /abonos/carga} según método y forma del path:
	 * {@code POST /abonos/carga} (inicia), {@code GET /abonos/carga/{id}} (estado),
	 * {@code POST /abonos/carga/{id}/detener} (detiene). Mismo patrón que {@link #testRuns} para
	 * un solo contexto HTTP con varias rutas.
	 */
	private Response loadCampaign(HttpExchange exchange, SpeiServer speiServer) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();

		Matcher stopMatcher = CAMPAIGN_STOP_PATH.matcher(path);
		if ("POST".equals(method) && stopMatcher.matches()) {
			return stopLoadCampaign(stopMatcher.group(1));
		}
		if ((path.equals("/abonos/carga") || path.equals("/abonos/carga/")) && "POST".equals(method)) {
			return startLoadCampaign(exchange, speiServer);
		}
		Matcher statusMatcher = CAMPAIGN_STATUS_PATH.matcher(path);
		if ("GET".equals(method) && statusMatcher.matches()) {
			return loadCampaignStatus(statusMatcher.group(1));
		}
		return Response.of(405, Map.of("error", "metodo-o-ruta-no-soportada"));
	}

	/**
	 * {@code POST /abonos/carga} -- inicia una campaña de volumen (spec 012). Cuerpo esperado:
	 * {@code {"modo": "sostenida"|"rampa", "tipoPg": N, "campos": {...},}} más, según el modo:
	 * sostenida -- {@code "tasaPorMinuto": N, "duracionSegundos": N (opcional, indefinida si se omite)};
	 * rampa -- {@code "tasaInicialPorMinuto": N, "incrementoPorMinuto": N, "segundosPorEscalon": N,
	 * "tasaMaxima": N (tope de seguridad)}.
	 */
	private Response startLoadCampaign(HttpExchange exchange, SpeiServer speiServer) throws IOException {
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request;
		try {
			request = JsonReader.readObject(rawBody);
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "json-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		Object tipoPgRaw = request.get("tipoPg");
		if (!(tipoPgRaw instanceof Number)) {
			return Response.of(400, Map.of("error", "falta-tipoPg", "detalle", "'tipoPg' es obligatorio y debe ser numérico."));
		}
		int tipoPg = ((Number) tipoPgRaw).intValue();

		Object camposRaw = request.get("campos");
		if (!(camposRaw instanceof Map<?, ?> camposMap)) {
			return Response.of(400, Map.of("error", "faltan-campos",
					"detalle", "'campos' es obligatorio: mapa de nombre de campo -> valor según PaymentType."));
		}
		Map<String, String> campos = new LinkedHashMap<>();
		for (var entry : camposMap.entrySet()) {
			campos.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
		}

		String modo = request.get("modo") instanceof String s ? s.toLowerCase(java.util.Locale.ROOT) : null;
		if (modo == null || (!modo.equals("sostenida") && !modo.equals("rampa"))) {
			return Response.of(400, Map.of("error", "modo-invalido", "detalle", "'modo' debe ser 'sostenida' o 'rampa'."));
		}

		String id = "carga-" + campaignSequence.incrementAndGet();
		LoadCampaign campaign;
		try {
			if (modo.equals("sostenida")) {
				int tasa = intField(request, "tasaPorMinuto", true, 0);
				Long duracion = request.get("duracionSegundos") instanceof Number n ? n.longValue() : null;
				campaign = LoadCampaign.sostenida(id, session, tipoPg, campos, tasa, duracion);
			} else {
				int tasaInicial = intField(request, "tasaInicialPorMinuto", true, 0);
				int incremento = intField(request, "incrementoPorMinuto", true, 0);
				int segundosPorEscalon = intField(request, "segundosPorEscalon", true, 0);
				int tasaMaxima = intField(request, "tasaMaxima", true, 0);
				campaign = LoadCampaign.rampa(id, session, tipoPg, campos, tasaInicial, incremento,
						segundosPorEscalon, tasaMaxima);
			}
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "parametro-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		loadCampaigns.put(id, campaign);
		campaign.start();
		return Response.ok(Map.of("status", "iniciada", "id", id, "modo", modo));
	}

	private int intField(Map<String, Object> request, String key, boolean required, int fallback) {
		Object raw = request.get(key);
		if (raw instanceof Number n) {
			return n.intValue();
		}
		if (required) {
			throw new IllegalArgumentException("'" + key + "' es obligatorio y debe ser numérico.");
		}
		return fallback;
	}

	private Response loadCampaignStatus(String id) {
		LoadCampaign campaign = loadCampaigns.get(id);
		if (campaign == null) {
			return Response.of(404, Map.of("error", "campana-no-encontrada", "id", id));
		}
		return Response.ok(campaign.status());
	}

	private Response stopLoadCampaign(String id) {
		LoadCampaign campaign = loadCampaigns.get(id);
		if (campaign == null) {
			return Response.of(404, Map.of("error", "campana-no-encontrada", "id", id));
		}
		campaign.stop();
		return Response.ok(campaign.status());
	}

	/** {@code POST /heartbeat/detener} -- spec 009: suspende el {@code AreYouAlive} saliente de la
	 *  sesión activa, para medir cuánto tarda minos en cerrar por su timeout de 6s. */
	private Response stopHeartbeat(HttpExchange exchange, SpeiServer speiServer) {
		if (!"POST".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		session.suspendHeartbeat();
		return Response.ok(Map.of("status", "heartbeat-suspendido", "runId", session.runId()));
	}

	private Response testRuns(HttpExchange exchange, H2Store store) {
		if (!"GET".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		String path = exchange.getRequestURI().getPath();
		if (path.equals("/test-runs") || path.equals("/test-runs/")) {
			var runs = store.listRuns().stream().map(run -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", run.id());
				m.put("startedAt", run.startedAt().toString());
				m.put("remoteAddress", run.remoteAddress());
				m.put("channel", run.channel());
				return (Object) m;
			}).toList();
			return Response.ok(Map.of("runs", runs));
		}

		Matcher matcher = RUN_EVENTS_PATH.matcher(path);
		if (matcher.matches()) {
			long runId;
			try {
				runId = Long.parseLong(matcher.group(1));
			} catch (NumberFormatException e) {
				return Response.of(400, Map.of("error", "id-invalido"));
			}
			var events = store.listEventsForRun(runId).stream().map(ev -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", ev.id());
				m.put("runId", ev.runId());
				m.put("occurredAt", ev.occurredAt().toString());
				m.put("direction", ev.direction());
				m.put("messageName", ev.messageName());
				m.put("opCode", ev.opCode());
				m.put("result", ev.result());
				m.put("detail", ev.detail());
				m.put("rawHex", ev.rawHex());
				return (Object) m;
			}).toList();
			return Response.ok(Map.of("runId", runId, "events", events));
		}

		return Response.of(404, Map.of("error", "ruta-no-encontrada"));
	}

	// ---- Spec 005 (capa A): variaciones de red ----

	/** {@code GET /capacidades} -- qué puede probarse en este despliegue y con qué límites. */
	private Response capacidades(HttpExchange exchange) {
		if (!"GET".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		return Response.ok(redVariacionRegistry.capacidades());
	}

	/** Enruta {@code GET/POST /red/variacion} y {@code POST /red/variacion/detener} -- mismo
	 *  patrón que {@link #loadCampaign} para varias rutas bajo un solo contexto HTTP. Sin
	 *  precondición de "sesión viva" (spec &sect;3): se puede armar un corte para
	 *  {@code post-Greeting} antes de que exista sesión. */
	private Response redVariacion(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();

		if ("POST".equals(method) && RED_VARIACION_DETENER_PATH.matcher(path).matches()) {
			return detenerRedVariacion();
		}
		if (path.equals("/red/variacion") || path.equals("/red/variacion/")) {
			if ("GET".equals(method)) {
				return estadoRedVariacion();
			}
			if ("POST".equals(method)) {
				return iniciarRedVariacion(exchange);
			}
		}
		return Response.of(405, Map.of("error", "metodo-o-ruta-no-soportada"));
	}

	private Response estadoRedVariacion() {
		return redVariacionRegistry.activa()
				.<Response>map(v -> Response.ok(Map.of("activa", true, "variacion", v.status())))
				.orElseGet(() -> Response.ok(Map.of("activa", false)));
	}

	private Response detenerRedVariacion() {
		return redVariacionRegistry.detener()
				.<Response>map(v -> Response.ok(Map.of("status", "detenida", "variacion", v.status())))
				.orElseGet(() -> Response.of(409, Map.of("error", "no-hay-variacion-activa")));
	}

	/**
	 * {@code POST /red/variacion} -- arma una variación de red (spec 005, capa A). Cuerpo esperado
	 * (comunes): {@code {"tipo": "retraso"|"corte"|"duplicacion"|"throttling", "quien": "...",
	 * "duracionSegundos": N (opcional)}}, más según {@code tipo}:
	 * <ul>
	 *   <li>retraso: {@code "punto", "ocurrencia" (entero|"siguiente"), "latenciaMs", "jitterMs" (opcional)}</li>
	 *   <li>corte: {@code "punto"} con prefijo {@code "post-"} (ej. {@code "post-ClvSim"}), {@code "ocurrencia"}</li>
	 *   <li>duplicacion: {@code "punto", "ocurrencia", "probabilidad"}</li>
	 *   <li>throttling: {@code "bytesPorSegundoOut", "bytesPorSegundoIn"}</li>
	 * </ul>
	 * {@code 409} si ya hay una variación activa (mensaje dice quién y desde cuándo); {@code 400}
	 * con el límite exacto y de dónde sale si algún parámetro está fuera de rango.
	 */
	private Response iniciarRedVariacion(HttpExchange exchange) throws IOException {
		String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request;
		try {
			request = JsonReader.readObject(rawBody);
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "json-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		RedVariacion.Tipo tipo = null;
		if (request.get("tipo") instanceof String s) {
			try {
				tipo = RedVariacion.Tipo.valueOf(s.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				// tipo queda null, se reporta abajo
			}
		}
		if (tipo == null) {
			return Response.of(400, Map.of("error", "tipo-invalido",
					"detalle", "'tipo' debe ser uno de: retraso, corte, duplicacion, throttling."));
		}

		String quien = request.get("quien") instanceof String s && !s.isBlank() ? s : null;
		if (quien == null) {
			return Response.of(400, Map.of("error", "falta-quien", "detalle", "'quien' es obligatorio."));
		}

		long duracionMaxima = config.redVariacionDuracionSegundosMaxima();
		long duracion = request.get("duracionSegundos") instanceof Number n
				? n.longValue() : config.redVariacionDuracionSegundosDefault();
		if (duracion <= 0 || duracion > duracionMaxima) {
			return Response.of(400, Map.of("error", "duracion-fuera-de-rango",
					"detalle", "'duracionSegundos' debe ser mayor a 0 y como máximo " + duracionMaxima
							+ " (red.variacion.duracionSegundosMaxima)."));
		}

		String id = "redvar-" + redVariacionSequence.incrementAndGet();
		RedVariacion nueva;
		try {
			nueva = switch (tipo) {
				case RETRASO -> construirRetraso(request, id, quien, duracion);
				case CORTE -> construirCorte(request, id, quien, duracion);
				case DUPLICACION -> construirDuplicacion(request, id, quien, duracion);
				case THROTTLING -> construirThrottling(request, id, quien, duracion);
			};
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "parametro-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		return redVariacionRegistry.iniciar(nueva)
				.<Response>map(v -> Response.ok(Map.of("status", "iniciada", "variacion", v.status())))
				.orElseGet(() -> {
					RedVariacion actual = redVariacionRegistry.activa().orElse(null);
					String detalle = actual == null
							? "Ya hay una variación activa."
							: "Ya hay una variación activa: " + actual.tipo() + " armada por '" + actual.quien()
									+ "' desde " + actual.armadaEn() + ".";
					return Response.of(409, Map.of("error", "variacion-ya-activa", "detalle", detalle));
				});
	}

	private RedVariacion construirRetraso(Map<String, Object> request, String id, String quien, long duracion) {
		String punto = requiredPunto(request);
		OcurrenciaSpec ocurrencia = parseOcurrencia(request);
		long latenciaMs = longField(request, "latenciaMs", true, 0);
		long jitterMs = longField(request, "jitterMs", false, 0);
		long retrasoMaximo = config.redVariacionRetrasoMaximoMs();
		if (latenciaMs < 0 || jitterMs < 0 || latenciaMs + jitterMs > retrasoMaximo) {
			throw new IllegalArgumentException("'latenciaMs' + 'jitterMs' no puede superar " + retrasoMaximo
					+ "ms (red.variacion.retrasoMaximoMs, derivado de minos.readTimeoutMs - "
					+ "spei.heartbeatIntervalMs - margen).");
		}
		return RedVariacion.retraso(id, quien, duracion, punto, ocurrencia.exacta(), ocurrencia.siguiente(),
				latenciaMs, jitterMs);
	}

	private RedVariacion construirCorte(Map<String, Object> request, String id, String quien, long duracion) {
		String puntoPost = requiredPunto(request);
		if (!puntoPost.startsWith("post-")) {
			throw new IllegalArgumentException("'punto' de un corte debe traer el prefijo 'post-', ej. 'post-ClvSim'.");
		}
		OcurrenciaSpec ocurrencia = parseOcurrencia(request);
		return RedVariacion.corte(id, quien, duracion, puntoPost, ocurrencia.exacta(), ocurrencia.siguiente());
	}

	private RedVariacion construirDuplicacion(Map<String, Object> request, String id, String quien, long duracion) {
		String punto = requiredPunto(request);
		OcurrenciaSpec ocurrencia = parseOcurrencia(request);
		double max = config.redVariacionDuplicacionProbabilidadMaxima();
		if (!(request.get("probabilidad") instanceof Number n)) {
			throw new IllegalArgumentException("'probabilidad' es obligatoria y debe ser numérica (0-" + max + ").");
		}
		double probabilidad = n.doubleValue();
		if (probabilidad <= 0 || probabilidad > max) {
			throw new IllegalArgumentException("'probabilidad' debe estar entre 0 (exclusivo) y " + max
					+ " (red.variacion.duplicacionProbabilidadMaxima).");
		}
		return RedVariacion.duplicacion(id, quien, duracion, punto, ocurrencia.exacta(), ocurrencia.siguiente(),
				probabilidad);
	}

	private RedVariacion construirThrottling(Map<String, Object> request, String id, String quien, long duracion) {
		long bytesOut = longField(request, "bytesPorSegundoOut", true, 0);
		long bytesIn = longField(request, "bytesPorSegundoIn", true, 0);
		if (bytesOut <= 0 || bytesIn <= 0) {
			throw new IllegalArgumentException("'bytesPorSegundoOut' y 'bytesPorSegundoIn' deben ser mayores a 0.");
		}
		return RedVariacion.throttling(id, quien, duracion, bytesOut, bytesIn);
	}

	private String requiredPunto(Map<String, Object> request) {
		if (!(request.get("punto") instanceof String s) || s.isBlank()) {
			throw new IllegalArgumentException("'punto' es obligatorio -- ver /capacidades para los puntos válidos.");
		}
		return s;
	}

	private record OcurrenciaSpec(Integer exacta, boolean siguiente) {
	}

	private OcurrenciaSpec parseOcurrencia(Map<String, Object> request) {
		Object raw = request.get("ocurrencia");
		if (raw instanceof Number n) {
			return new OcurrenciaSpec(n.intValue(), false);
		}
		if (raw instanceof String s && s.equalsIgnoreCase("siguiente")) {
			return new OcurrenciaSpec(null, true);
		}
		throw new IllegalArgumentException("'ocurrencia' es obligatoria: un entero o la cadena 'siguiente'.");
	}

	private long longField(Map<String, Object> request, String key, boolean required, long fallback) {
		Object raw = request.get(key);
		if (raw instanceof Number n) {
			return n.longValue();
		}
		if (required) {
			throw new IllegalArgumentException("'" + key + "' es obligatorio y debe ser numérico.");
		}
		return fallback;
	}

	// ---- Spec 014: recepción y liquidación de pagos ----

	/** Enruta {@code GET/POST /pagos/rechazo-forzado} y {@code POST .../cancelar} -- mismo patrón
	 *  que {@link #redVariacion}. */
	private Response rechazoForzado(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();

		if ("POST".equals(method) && RECHAZO_FORZADO_CANCELAR_PATH.matcher(path).matches()) {
			return cancelarRechazoForzado();
		}
		if (path.equals("/pagos/rechazo-forzado") || path.equals("/pagos/rechazo-forzado/")) {
			if ("GET".equals(method)) {
				return estadoRechazoForzado();
			}
			if ("POST".equals(method)) {
				return iniciarRechazoForzado(exchange);
			}
		}
		return Response.of(405, Map.of("error", "metodo-o-ruta-no-soportada"));
	}

	private Response estadoRechazoForzado() {
		return rechazoForzadoRegistry.activo()
				.<Response>map(r -> Response.ok(Map.of("activo", true, "rechazo", r.status())))
				.orElseGet(() -> Response.ok(Map.of("activo", false)));
	}

	private Response cancelarRechazoForzado() {
		return rechazoForzadoRegistry.detener()
				.<Response>map(r -> Response.ok(Map.of("status", "cancelado", "rechazo", r.status())))
				.orElseGet(() -> Response.of(409, Map.of("error", "no-hay-rechazo-activo")));
	}

	/**
	 * {@code POST /pagos/rechazo-forzado} -- requisito explícito de la spec funcional de la
	 * iniciativa (forzar un rechazo a propósito para probar cómo reacciona minos). Cuerpo:
	 * {@code {"motivo": N, "quien": "...", "trackingKey": "opcional"}} -- {@code motivo} es
	 * obligatorio (1-30, ver catálogo de devoluciones); sin {@code trackingKey}, cubre la
	 * próxima orden de cualquier clave.
	 */
	private Response iniciarRechazoForzado(HttpExchange exchange) throws IOException {
		String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request;
		try {
			request = JsonReader.readObject(rawBody);
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "json-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		if (!(request.get("motivo") instanceof Number n)) {
			return Response.of(400, Map.of("error", "falta-motivo",
					"detalle", "'motivo' es obligatorio y debe ser numérico (1-30, ver catálogo de devoluciones)."));
		}
		MotivoRechazo motivo;
		try {
			motivo = MotivoRechazo.porCodigo(n.intValue());
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "motivo-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		String quien = request.get("quien") instanceof String s && !s.isBlank() ? s : null;
		if (quien == null) {
			return Response.of(400, Map.of("error", "falta-quien", "detalle", "'quien' es obligatorio."));
		}
		String trackingKey = request.get("trackingKey") instanceof String s && !s.isBlank() ? s : null;

		String id = "rechazo-" + rechazoForzadoSequence.incrementAndGet();
		RechazoForzado nuevo = new RechazoForzado(id, quien, motivo, trackingKey);
		return rechazoForzadoRegistry.iniciar(nuevo)
				.<Response>map(r -> Response.ok(Map.of("status", "armado", "rechazo", r.status())))
				.orElseGet(() -> {
					RechazoForzado actual = rechazoForzadoRegistry.activo().orElse(null);
					String detalle = actual == null
							? "Ya hay un rechazo forzado activo."
							: "Ya hay un rechazo forzado activo: armado por '" + actual.quien()
									+ "' desde " + actual.armadaEn() + ".";
					return Response.of(409, Map.of("error", "rechazo-ya-activo", "detalle", detalle));
				});
	}

	/** Enruta {@code POST /pagos/cargos} (manual), {@code GET .../pendientes} y
	 *  {@code POST .../liquidar-lote} -- mismo patrón que {@link #loadCampaign}. */
	private Response pagosCargos(HttpExchange exchange, SpeiServer speiServer) throws Exception {
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();

		if ("GET".equals(method) && CARGOS_PENDIENTES_PATH.matcher(path).matches()) {
			return cargosPendientes(speiServer);
		}
		if ("POST".equals(method) && CARGOS_LIQUIDAR_LOTE_PATH.matcher(path).matches()) {
			return liquidarLoteCargos(speiServer);
		}
		if ((path.equals("/pagos/cargos") || path.equals("/pagos/cargos/")) && "POST".equals(method)) {
			return triggerCargosManual(exchange, speiServer);
		}
		return Response.of(405, Map.of("error", "metodo-o-ruta-no-soportada"));
	}

	private Response cargosPendientes(SpeiServer speiServer) {
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		var pendientes = store.listarCargosPendientes(session.operationalDate()).stream().map(p -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.id());
			m.put("entityIndex", p.entityIndex());
			m.put("entityCode", p.entityCode());
			m.put("instructionFolio", p.instructionFolio());
			m.put("internalFolio", p.internalFolio());
			m.put("amount", p.amount());
			return (Object) m;
		}).toList();
		return Response.ok(Map.of("pendientes", pendientes));
	}

	/** {@code POST /pagos/cargos/liquidar-lote} -- manda un solo {@code Cargos} con todo lo
	 *  acumulado del día operativo vigente (modo {@code acumulado}, spec 014 &sect;4); el
	 *  {@code folio} del mensaje consolidado es el {@code folioPack} más reciente entre las
	 *  entradas (decisión de implementación, no documentada en ningún lado -- ver specs/014). */
	private Response liquidarLoteCargos(SpeiServer speiServer) throws Exception {
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		var pendientes = store.listarCargosPendientes(session.operationalDate());
		if (pendientes.isEmpty()) {
			return Response.of(409, Map.of("error", "no-hay-cargos-pendientes"));
		}
		List<CargosCodec.CargoEntry> entries = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		int folio = 0;
		for (var p : pendientes) {
			entries.add(new CargosCodec.CargoEntry(p.entityIndex(), p.entityCode(), p.instructionFolio(),
					(short) p.internalFolio()));
			total = total.add(p.amount());
			folio = Math.max(folio, p.instructionFolio());
		}
		session.sendCargos(folio, entries, total);
		store.limpiarCargosPendientes(session.operationalDate());
		return Response.ok(Map.of("status", "liquidado", "folio", folio, "entradas", entries.size(), "monto", total));
	}

	/**
	 * {@code POST /pagos/cargos} -- {@code Cargos} manual arbitrario, no ligado a una orden real
	 * (spec 014 &sect;4) -- útil para probar escenarios de saldo sin depender de un
	 * {@code OrdenTopoV}. Cuerpo: {@code {"folio": N, "entradas": [{"entityIndex": N,
	 * "entityCode": N, "instructionFolio": N, "internalFolio": N, "monto": N}, ...]}}.
	 */
	private Response triggerCargosManual(HttpExchange exchange, SpeiServer speiServer) throws IOException {
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request;
		try {
			request = JsonReader.readObject(rawBody);
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "json-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		Object entradasRaw = request.get("entradas");
		if (!(entradasRaw instanceof List<?> lista) || lista.isEmpty()) {
			return Response.of(400, Map.of("error", "faltan-entradas",
					"detalle", "'entradas' es obligatorio: lista de {entityIndex, entityCode, instructionFolio, internalFolio, monto}."));
		}

		List<CargosCodec.CargoEntry> entries = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		int folio;
		try {
			folio = intField(request, "folio", true, 0);
			for (Object o : lista) {
				if (!(o instanceof Map<?, ?> rawEntrada)) {
					return Response.of(400, Map.of("error", "entrada-invalida", "detalle", "Cada entrada debe ser un objeto."));
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> entrada = (Map<String, Object>) rawEntrada;
				int entityIndex = intField(entrada, "entityIndex", true, 0);
				int entityCode = intField(entrada, "entityCode", true, 0);
				int instructionFolio = intField(entrada, "instructionFolio", true, 0);
				int internalFolio = intField(entrada, "internalFolio", true, 0);
				if (!(entrada.get("monto") instanceof Number montoRaw)) {
					return Response.of(400, Map.of("error", "falta-monto", "detalle", "cada entrada requiere 'monto' numérico."));
				}
				entries.add(new CargosCodec.CargoEntry(entityIndex, entityCode, instructionFolio, (short) internalFolio));
				total = total.add(new BigDecimal(montoRaw.toString()));
			}
		} catch (IllegalArgumentException e) {
			return Response.of(400, Map.of("error", "parametro-invalido", "detalle", String.valueOf(e.getMessage())));
		}

		try {
			session.sendCargos(folio, entries, total);
		} catch (Exception e) {
			logger.error("[Control] Error mandando Cargos manual: {}", e.getMessage(), e);
			return Response.of(500, Map.of("error", "error-interno", "detalle", String.valueOf(e.getMessage())));
		}
		return Response.ok(Map.of("status", "enviado", "folio", folio, "entradas", entries.size(), "monto", total));
	}

	/** {@code GET /pagos/saldo} -- saldo del día operativo vigente (spec 014 &sect;4). */
	private Response saldo(HttpExchange exchange, SpeiServer speiServer) {
		if (!"GET".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		H2Store.SaldoDia saldo = store.saldoDelDia(session.operationalDate(), config.cargosBalanceInicial(),
				config.cargosReservedBalanceInicial());
		return Response.ok(Map.of("balance", saldo.balance(), "reservedBalance", saldo.reservedBalance(),
				"diaOperativo", session.operationalDate().toString()));
	}

	/** {@code POST /dia/cerrar} -- {@code LiquidacionFinal} (spec 014 &sect;5), solo manual.
	 *  Cuerpo opcional: {@code {"montoFinal": N}} (default 0). */
	private Response cerrarDia(HttpExchange exchange, SpeiServer speiServer) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			return Response.methodNotAllowed();
		}
		SpeiSession session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			return Response.of(409, Map.of(
					"error", "no-hay-sesion-viva",
					"detalle", "No hay una sesión SPEI viva todavía -- conecta minos primero (ver README)."));
		}
		String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		BigDecimal montoFinal = BigDecimal.ZERO;
		if (!rawBody.isBlank()) {
			Map<String, Object> request;
			try {
				request = JsonReader.readObject(rawBody);
			} catch (IllegalArgumentException e) {
				return Response.of(400, Map.of("error", "json-invalido", "detalle", String.valueOf(e.getMessage())));
			}
			if (request.get("montoFinal") instanceof Number n) {
				montoFinal = new BigDecimal(n.toString());
			}
		}
		try {
			session.sendLiquidacionFinal(montoFinal);
		} catch (Exception e) {
			logger.error("[Control] Error mandando LiquidacionFinal: {}", e.getMessage(), e);
			return Response.of(500, Map.of("error", "error-interno", "detalle", String.valueOf(e.getMessage())));
		}
		return Response.ok(Map.of("status", "enviado", "montoFinal", montoFinal));
	}

	// ---- Infraestructura interna del handler ----

	private interface EndpointHandler {
		Response handle(HttpExchange exchange) throws Exception;
	}

	private record Response(int status, Object body) {
		static Response ok(Object body) {
			return new Response(200, body);
		}

		static Response of(int status, Object body) {
			return new Response(status, body);
		}

		static Response methodNotAllowed() {
			return new Response(405, Map.of("error", "metodo-no-soportado"));
		}
	}

	private void dispatch(HttpExchange exchange, EndpointHandler handler) {
		try {
			Response response = handler.handle(exchange);
			writeJson(exchange, response.status(), response.body());
		} catch (Exception e) {
			logger.error("[Control] Error atendiendo {} {}: {}",
					exchange.getRequestMethod(), exchange.getRequestURI(), e.getMessage(), e);
			try {
				writeJson(exchange, 500, Map.of("error", "error-interno", "detalle", String.valueOf(e.getMessage())));
			} catch (IOException ignored) {
				// no hay mucho más que hacer si ni siquiera se puede mandar el error
			}
		} finally {
			exchange.close();
		}
	}

	private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(payload);
		}
	}
}
