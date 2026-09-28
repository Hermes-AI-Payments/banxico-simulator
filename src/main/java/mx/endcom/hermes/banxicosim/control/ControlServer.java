package mx.endcom.hermes.banxicosim.control;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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

import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.LoadCampaign;
import mx.endcom.hermes.banxicosim.spei.SessionClosure;
import mx.endcom.hermes.banxicosim.spei.SpeiServer;
import mx.endcom.hermes.banxicosim.spei.SpeiSession;

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
 * </ul>
 */
public final class ControlServer {

	private static final Logger logger = LoggerFactory.getLogger(ControlServer.class);
	private static final Pattern RUN_EVENTS_PATH = Pattern.compile("^/test-runs/(\\d+)/events/?$");
	private static final Pattern CAMPAIGN_STOP_PATH = Pattern.compile("^/abonos/carga/([^/]+)/detener/?$");
	private static final Pattern CAMPAIGN_STATUS_PATH = Pattern.compile("^/abonos/carga/([^/]+)/?$");

	private final HttpServer httpServer;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	// Spec 012 -- registro de campañas de carga en curso/terminadas, vivas mientras el proceso
	// siga arriba (no persistidas en H2 -- son corridas efímeras de prueba, no eventos de protocolo).
	private final Map<String, LoadCampaign> loadCampaigns = new ConcurrentHashMap<>();
	private final AtomicLong campaignSequence = new AtomicLong();

	public ControlServer(int port, SpeiServer speiServer, H2Store store) throws IOException {
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
