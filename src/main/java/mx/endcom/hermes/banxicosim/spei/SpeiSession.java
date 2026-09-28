package mx.endcom.hermes.banxicosim.spei;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mx.endcom.hermes.banxicosim.config.SimConfig;
import mx.endcom.hermes.banxicosim.crypto.RsaCipher;
import mx.endcom.hermes.banxicosim.crypto.SimulatorIdentity;
import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.messages.AbonosCodec;
import mx.endcom.hermes.banxicosim.spei.messages.AcuseReciboCodec;
import mx.endcom.hermes.banxicosim.spei.messages.ClvSimCodec;
import mx.endcom.hermes.banxicosim.spei.messages.EnSesionCodec;
import mx.endcom.hermes.banxicosim.spei.messages.MsjCatalogosCodec;
import mx.endcom.hermes.banxicosim.spei.messages.OrdenTopoVCodec;
import mx.endcom.hermes.banxicosim.spei.messages.ReenvioCodec;
import mx.endcom.hermes.banxicosim.validation.OrderFieldValidator;
import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.crypto.AesCipher;

/**
 * Maneja una conexión del socket SPEI principal: todo el enlace (Fases 1-3) y la fase de
 * operación (Fases 4-5) para esa conexión.
 *
 * <p>Secuencia (ver 02_especificacion_tecnica.md &sect;3, pero con {@code EnSesion} y
 * {@code ClvSim} EN ORDEN INVERSO al que describe esa spec — ver nota abajo):</p>
 * <ol>
 *   <li>Espera {@code ConexionMessage} (código 16, sin cuerpo) — lo manda minos primero
 *       ({@code MinosServiceImpl.java:239-241}).</li>
 *   <li>Manda {@code GreetingMessage} (código 247).</li>
 *   <li>Manda {@code SmLoginReqMessage} (código 254); espera {@code LoginMessage} (código 1).</li>
 *   <li>Manda {@code EnSesionMessage} (código 13, particionado sin cifrar).</li>
 *   <li>Manda {@code ClvSimMessage} (código 80); espera {@code RespClvSimMessage} (código 221) —
 *       a partir de aquí hay llave de sesión AES.</li>
 *   <li>Manda {@code MsjCatalogosMessage} (código 31, cifrado AES). La sesión queda "viva".</li>
 *   <li>Atiende {@code Reenvio}/{@code InicioSesionCifrada} (mensajes que minos manda sin que la
 *       spec técnica los liste explícitamente, pero que el código real sí exige — ver
 *       {@code ReenvioCodec} y el manejo de InicioSesionCifrada abajo) y, en operación,
 *       {@code OrdenTopoV} (Fase 4) y puede mandar {@code Abonos} (Fase 5).</li>
 * </ol>
 *
 * <p><b>Por qué el orden está invertido contra 02_especificacion_tecnica.md &sect;3 (que pone
 * ClvSim antes de EnSesion):</b> encontrado en despliegue real 2026-09-14 contra minos real.
 * {@code EnSesionMessageHandler.handle()} (repo minos) llama {@code Spei.resetSession()} como
 * primera línea, y eso limpia {@code Spei.encryptKey}/{@code encryptVector} — los que
 * {@code ClvSimMessageHandler} acababa de establecer. Con el orden de la spec (ClvSim→EnSesion),
 * el reset borra la llave de sesión AES justo antes de que {@code MsjCatalogos} la necesite, y
 * minos truena con {@code IllegalArgumentException: Missing argument} al desencriptar
 * ({@code SpeiInputEncryptedPartitionedMessage.decryptBody}). Mandando EnSesion primero, el
 * reset ocurre ANTES de que ClvSim establezca la llave, y esta sobrevive hasta MsjCatalogos. La
 * spec no se corrigió (no se confirmó con Miguel Zavala, dueño de spec) — este código es lo que
 * de verdad conecta contra minos real, verificado con `GET /session` llegando a
 * {@code "alive":true, "fase":"VIVA"}.</p>
 */
public final class SpeiSession implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(SpeiSession.class);

	private final Socket socket;
	private final SimulatorIdentity identity;
	private final PublicKey minosPublicKey; // para cifrar ClvSim y verificar firmas de minos
	private final SimConfig config;
	private final H2Store store;
	private final long runId;
	private final OrderFieldValidator validator = new OrderFieldValidator();

	private DataOutputStream out;
	private byte[] sessionKey;
	private byte[] sessionIv;
	private volatile boolean alive = false;
	private volatile Phase phase = Phase.CONECTANDO;
	private volatile LocalDate operationalDate;
	private volatile Instant aliveSince;
	/** Causa fijada por quien cierra la sesión a propósito (heartbeat roto, minos avisó que
	 *  cierra) -- tiene prioridad sobre la excepción que ese cierre provoca en la lectura. */
	private volatile SessionClosure requestedClosure;
	private volatile SessionClosure closure;

	/** Todo envío por {@code out} pasa por este lock -- el heartbeat corre en su propio hilo
	 *  (ver {@link #startHeartbeat()}) y puede coincidir con un envío del hilo principal
	 *  ({@code run()}/{@code mainLoop}); sin este lock, dos escrituras concurrentes al mismo
	 *  socket podrían intercalar bytes y corromper el framing. */
	private final Object writeLock = new Object();
	private ScheduledExecutorService heartbeat;

	/** Fase del handshake/operación alcanzada por esta sesión -- expuesta por la API de control
	 *  ({@code GET /session}) para observabilidad; no forma parte del protocolo SPEI. */
	public enum Phase {
		CONECTANDO,
		CONEXION_RECIBIDA,
		GREETING_ENVIADO,
		LOGIN_RECIBIDO,
		CLVSIM_COMPLETADO,
		EN_SESION_ENVIADO,
		VIVA,
		TERMINADA
	}

	public SpeiSession(Socket socket, SimulatorIdentity identity, PublicKey minosPublicKey, SimConfig config,
			H2Store store) {
		this.socket = socket;
		this.identity = identity;
		this.minosPublicKey = minosPublicKey;
		this.config = config;
		this.store = store;
		this.runId = store.newRun("SPEI", socket.getRemoteSocketAddress().toString());
	}

	public boolean isAlive() {
		return alive;
	}

	/** Fase de handshake/operación alcanzada -- ver {@link Phase}. */
	public Phase phase() {
		return phase;
	}

	/** Día operativo declarado en {@code EnSesion} (fecha con la que arrancó la sesión viva),
	 *  o {@code null} si la sesión todavía no llega a esa fase. */
	public LocalDate operationalDate() {
		return operationalDate;
	}

	public long runId() {
		return runId;
	}

	/** Dirección remota (minos) de esta conexión. Sigue disponible después de cerrada la
	 *  conexión -- {@link Socket#getRemoteSocketAddress()} conserva el valor cacheado. */
	public String remoteAddress() {
		var address = socket.getRemoteSocketAddress();
		return address == null ? null : address.toString();
	}

	@Override
	public void run() {
		Exception failure = null;
		try (socket;
				DataInputStream in = new DataInputStream(socket.getInputStream());
				DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {
			this.out = dataOut;
			logger.info("[SPEI] Conexión entrante de {}", socket.getRemoteSocketAddress());

			awaitConexion(in);
			sendGreeting();
			sendSmLoginReq();
			awaitLogin(in);
			sendEnSesion();
			ClvSimCodec.SessionKeys keys = performClvSim(in);
			this.sessionKey = keys.key();
			this.sessionIv = keys.iv();
			sendMsjCatalogos();
			alive = true;
			phase = Phase.VIVA;
			aliveSince = Instant.now();
			startHeartbeat();
			logger.info("[SPEI] Sesión viva (handshake completo, fases 1-3 cumplidas)");

			mainLoop(in);
		} catch (Exception e) {
			failure = e;
			if (requestedClosure != null) {
				logger.info("[SPEI] Sesión cerrada ({})", requestedClosure.cause().code());
			} else if (e instanceof java.io.EOFException) {
				logger.info("[SPEI] Conexión cerrada por minos ({})", socket.getRemoteSocketAddress());
			} else {
				logger.error("[SPEI] Sesión terminada con error: {}", e.getMessage(), e);
			}
		} finally {
			alive = false;
			phase = Phase.TERMINADA;
			stopHeartbeat();
			recordClosure(failure);
		}
	}

	/** Spec 013 -- deja en la bitácora cómo terminó la sesión. La causa pedida explícitamente
	 *  gana sobre la excepción, porque esa excepción es consecuencia del propio cierre. */
	private void recordClosure(Exception failure) {
		SessionClosure c = requestedClosure;
		if (c == null) {
			c = failure != null ? SessionClosure.fromException(failure)
					: SessionClosure.now(SessionClosure.Cause.ERROR_IO, "socket cerrado sin causa registrada");
		}
		closure = c;
		store.logEvent(runId, "INTERNO", "CierreSesion", 0, c.cause().code(), c.detail(), null);
	}

	/** Cierra la sesión dejando registrada la causa. Solo cuenta la primera causa pedida. */
	private void requestClose(SessionClosure.Cause cause, String detail) {
		synchronized (this) {
			if (requestedClosure == null) {
				requestedClosure = SessionClosure.now(cause, detail);
			}
		}
		try {
			socket.close();
		} catch (Exception ignored) {
			// ya estaba cerrado
		}
	}

	/** Cómo terminó la sesión (spec 013), o {@code null} si sigue abierta. */
	public SessionClosure closure() {
		return closure;
	}

	/** Cuándo quedó viva (handshake completo), o {@code null} si no llegó a esa fase. */
	public Instant aliveSince() {
		return aliveSince;
	}

	// ---- Fase 1 ----

	private void awaitConexion(DataInputStream in) throws Exception {
		Frame frame = Frame.read(in);
		if (frame.operation() != SpeiProtocol.OP_CONEXION) {
			throw new IllegalStateException("Se esperaba ConexionMessage (16), llegó " + frame.operation());
		}
		store.logEvent(runId, "IN", "Conexion", frame.operation(), "recibido", null, null);
		phase = Phase.CONEXION_RECIBIDA;
		logger.info("[SPEI] << Conexion");
	}

	private void sendGreeting() throws Exception {
		synchronized (writeLock) {
			Frame.of(SpeiProtocol.OP_GREETING, new byte[0]).writeTo(out);
		}
		store.logEvent(runId, "OUT", "Greeting", SpeiProtocol.OP_GREETING, "enviado", null, null);
		phase = Phase.GREETING_ENVIADO;
		logger.info("[SPEI] >> Greeting");
	}

	// ---- Fase 2 ----

	private void sendSmLoginReq() throws Exception {
		synchronized (writeLock) {
			Frame.of(SpeiProtocol.OP_SMLOGINREQ, new byte[0]).writeTo(out);
		}
		store.logEvent(runId, "OUT", "SmLoginReq", SpeiProtocol.OP_SMLOGINREQ, "enviado", null, null);
		logger.info("[SPEI] >> SmLoginReq");
	}

	private void awaitLogin(DataInputStream in) throws Exception {
		Frame frame = Frame.read(in);
		if (frame.operation() != SpeiProtocol.OP_LOGIN) {
			throw new IllegalStateException("Se esperaba LoginMessage (1), llegó " + frame.operation());
		}
		String user = new ByteReader(frame.body()).readCString();
		store.logEvent(runId, "IN", "Login", frame.operation(), "recibido", "usuario=" + user, frame.body());
		phase = Phase.LOGIN_RECIBIDO;
		logger.info("[SPEI] << Login, usuario minos: {}", user);
	}

	private ClvSimCodec.SessionKeys performClvSim(DataInputStream in) throws Exception {
		if (minosPublicKey == null) {
			logger.warn("[SPEI] No hay llave pública de minos configurada; ClvSim se manda igual "
					+ "pero minos NO podrá desencriptar la llave de sesión. Ver README.");
		}
		ClvSimCodec.ClvSimBody clvSim = ClvSimCodec.build(minosPublicKey, identity.privateKey());
		synchronized (writeLock) {
			Frame.of(SpeiProtocol.OP_CLVSIM, clvSim.bytes()).writeTo(out);
		}
		store.logEvent(runId, "OUT", "ClvSim", SpeiProtocol.OP_CLVSIM, "enviado", null, clvSim.bytes());
		logger.info("[SPEI] >> ClvSim (reto RSA de sesión)");

		// minos procesa EnSesion de forma async (incluye ida y vuelta a ARA por los certificados)
		// en paralelo a como procesa este ClvSim -- no hay garantía de cuál de las dos respuestas
		// (InicioSesionCifrada, cola de EnSesion; RespClvSim, cola de ClvSim) llega primero al
		// socket. Encontrado en despliegue real 2026-09-14: con EnSesion mandado antes que ClvSim
		// (ver nota de la clase), InicioSesionCifrada casi siempre gana la carrera. Se procesa
		// aquí si aparece, y se sigue esperando el RespClvSim real.
		Frame resp = Frame.read(in);
		while (resp.operation() == SpeiProtocol.OP_INICIO_SESION_CIFRADA) {
			handleInicioSesionCifrada(resp);
			resp = Frame.read(in);
		}
		if (resp.operation() != SpeiProtocol.OP_RESP_CLVSIM) {
			throw new IllegalStateException("Se esperaba RespClvSim (221), llegó " + resp.operation());
		}
		ClvSimCodec.RespClvSimResult result = ClvSimCodec.verifyResponse(resp.body(),
				clvSim.sessionKeys().rawSymmetricKey(), minosPublicKey);
		store.logEvent(runId, "IN", "RespClvSim", resp.operation(), "recibido",
				"firmaVerificada=" + result.signatureVerified(), resp.body());
		phase = Phase.CLVSIM_COMPLETADO;
		logger.info("[SPEI] << RespClvSim, firma verificada={}", result.signatureVerified());
		return clvSim.sessionKeys();
	}

	// ---- Fase 3 ----

	private void sendEnSesion() throws Exception {
		EnSesionCodec.EntityCert own = new EnSesionCodec.EntityCert(
				config.ownEntityCode(), "BANXICOSIM", identity.certificateNumber());
		EnSesionCodec.EntityCert minos = new EnSesionCodec.EntityCert(
				config.minosEntityCode(), config.minosEntityName(), config.minosCertificateNumber());

		byte[] payload = EnSesionCodec.buildBody(
				// Spec 001: config.maxMessageLength() en vez de 65535 fijo -- bajarlo fuerza que
				// minos parta de verdad sus propios envíos hacia el simulador (ver SimConfig).
				LocalDate.now(), config.maxMessageLength(), 4096, own, minos,
				"simulador-hermes-banxico".getBytes(StandardCharsets.ISO_8859_1), 20);
		byte[] body = WireFraming.withLengthPrefix(payload);
		synchronized (writeLock) {
			Frame.of(SpeiProtocol.OP_ENSESION, body).writeTo(out);
		}
		store.logEvent(runId, "OUT", "EnSesion", SpeiProtocol.OP_ENSESION, "enviado", null, body);
		operationalDate = LocalDate.now();
		phase = Phase.EN_SESION_ENVIADO;
		logger.info("[SPEI] >> EnSesion (entidad propia={}, entidad minos={})",
				config.ownEntityCode(), config.minosEntityCode());
	}

	private void sendMsjCatalogos() throws Exception {
		// Spec 008: config.catalogosPoblados() decide entre el cuerpo vacío de v1 y catálogos
		// sintéticos poblados -- no hay evidencia de que minos reaccione distinto a uno u otro,
		// ver specs/008-msjcatalogos-contenido-real.md "Preguntas abiertas".
		boolean poblados = config.catalogosPoblados();
		byte[] payload = poblados
				? MsjCatalogosCodec.buildPopulatedBody(MsjCatalogosCodec.syntheticCatalogs())
				: MsjCatalogosCodec.buildEmptyBody();
		byte[] body = WireFraming.buildEncryptedPartitioned(payload, sessionKey, sessionIv);
		synchronized (writeLock) {
			Frame.of(SpeiProtocol.OP_MSJCATALOGOS, body).writeTo(out);
		}
		store.logEvent(runId, "OUT", "MsjCatalogos", SpeiProtocol.OP_MSJCATALOGOS, "enviado", null, body);
		logger.info("[SPEI] >> MsjCatalogos ({})", poblados ? "catálogos poblados, spec 008" : "catálogos vacíos, v1");
	}

	/**
	 * Heartbeat obligatorio: {@code minos} fija un read-timeout de 6s en su socket SPEI
	 * ({@code SpeiSocketServiceImpl.java:109}, {@code setSoTimeout(6000)}) y NO tolera ni un solo
	 * timeout -- {@code SpeiInputListener.run()} cierra la conexión de inmediato en cualquier
	 * {@code IOException}, sin reintentos. minos solo *recibe* {@code AreYouAliveMessage} (nunca
	 * la manda) y solo *manda* {@code IAmAliveMessage} en respuesta -- el emisor del heartbeat es
	 * Banxico, así que el simulador tiene que mandarlo proactivamente o minos da por muerta la
	 * sesión aunque todo lo demás esté bien. Detectado en pruebas reales contra minos (no estaba
	 * en la spec técnica original ni en el arnés de verificación previo). Cada 3s, bien debajo del
	 * límite de 6s.
	 */
	private void startHeartbeat() {
		heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "spei-heartbeat-" + runId);
			t.setDaemon(true);
			return t;
		});
		heartbeat.scheduleAtFixedRate(() -> {
			try {
				synchronized (writeLock) {
					Frame.of(SpeiProtocol.OP_AREYOUALIVE, new byte[0]).writeTo(out);
				}
				logger.debug("[SPEI] >> AreYouAlive (heartbeat)");
			} catch (Exception e) {
				logger.warn("[SPEI] Heartbeat falló, cerrando la sesión: {}", e.getMessage());
				heartbeat.shutdown();
				// Si ya no se puede escribir, la conexión está rota: sin cerrarla, la lectura puede
				// quedarse bloqueada indefinidamente si minos desapareció sin mandar FIN.
				requestClose(SessionClosure.Cause.HEARTBEAT_FALLO, e.getMessage());
			}
		}, 3, 3, TimeUnit.SECONDS);
	}

	private void stopHeartbeat() {
		if (heartbeat != null) {
			heartbeat.shutdownNow();
		}
	}

	/**
	 * Spec 009 -- suspende el envío de {@code AreYouAlive} deliberadamente, para probar que minos
	 * cierra la sesión tras su timeout de lectura de 6s (ver el javadoc de {@link #startHeartbeat}).
	 * A diferencia de {@link #stopHeartbeat()} (que también se llama al cerrar la sesión
	 * normalmente), este método es el punto de entrada público para un escenario de prueba
	 * disparado desde la API de control -- misma acción, distinto propósito documentado.
	 */
	public void suspendHeartbeat() {
		logger.warn("[SPEI] Heartbeat suspendido deliberadamente (spec 009) -- minos debería cerrar "
				+ "la sesión en ~6s si no ha corrido ya un ciclo de AreYouAlive antes de esto");
		stopHeartbeat();
	}

	// ---- Operación (Fases 4-5) ----

	private void mainLoop(DataInputStream in) throws Exception {
		while (!socket.isClosed()) {
			Frame frame = Frame.read(in);
			switch (frame.operation()) {
				case SpeiProtocol.OP_INICIO_SESION_CIFRADA -> handleInicioSesionCifrada(frame);
				case 207 -> handleReenvio(frame); // ReenvioMessage.MSG_CODE, ver ReenvioCodec
				case SpeiProtocol.OP_ORDEN_TOPOV -> handleOrdenTopoV(frame, in);
				case SpeiProtocol.OP_IAMALIVE -> logger.info("[SPEI] << IAmAlive");
				case SpeiProtocol.OP_DEADSRVR, SpeiProtocol.OP_SMTTYCLOSE, SpeiProtocol.OP_NOSERVICE -> {
					logger.info("[SPEI] minos cerró la sesión (op {})", frame.operation());
					requestClose(SessionClosure.Cause.MINOS_CERRO, "op " + frame.operation());
					return;
				}
				default -> logger.warn("[SPEI] Código de operación no manejado en v1: {} ({} bytes de cuerpo)",
						frame.operation(), frame.body().length);
			}
		}
	}

	private void handleInicioSesionCifrada(Frame frame) {
		try {
			ByteReader r = new ByteReader(frame.body());
			String minosCertNumber = r.readCString();
			String encryptedRandomB64 = r.readCString();
			String signatureB64 = r.readCString();
			byte[] nonce = RsaCipher.decryptFromBase64(
					encryptedRandomB64.getBytes(StandardCharsets.US_ASCII), identity.privateKey());
			boolean verified = false;
			if (minosPublicKey != null) {
				byte[] rawSig = RsaCipher.decodeBase64(signatureB64.getBytes(StandardCharsets.US_ASCII));
				verified = RsaCipher.verify(nonce, rawSig, minosPublicKey);
			}
			store.logEvent(runId, "IN", "InicioSesionCifrada", frame.operation(), "recibido",
					"firmaVerificada=" + verified, frame.body());
			logger.info("[SPEI] << InicioSesionCifrada, nonce desencriptado correctamente, firma verificada={}",
					verified);
		} catch (Exception e) {
			logger.warn("[SPEI] No fue posible procesar InicioSesionCifrada: {}", e.getMessage());
		}
	}

	private void handleReenvio(Frame frame) throws Exception {
		byte[] plaintext = AesCipher.decrypt(frame.body(), sessionKey, sessionIv);
		ReenvioCodec.Reenvio reenvio = ReenvioCodec.parse(plaintext);
		store.logEvent(runId, "IN", "Reenvio", frame.operation(), "recibido",
				"bytesProcesados=" + reenvio.processedBytes(), frame.body());
		logger.info("[SPEI] << Reenvio (bytesProcesados={}), respondo FinReenvio sin reenvÍo real (v1)",
				reenvio.processedBytes());

		byte[] finReenvioPlain = ReenvioCodec.buildFinReenvioBody();
		byte[] finReenvioCipher = AesCipher.encrypt(finReenvioPlain, sessionKey, sessionIv);
		synchronized (writeLock) {
			Frame.of(32, finReenvioCipher).writeTo(out); // FinReenvioMessage.OP = 32 (ver ToSpeiInputMessage)
		}
		store.logEvent(runId, "OUT", "FinReenvio", 32, "enviado", null, finReenvioCipher);
		logger.info("[SPEI] >> FinReenvio");
	}

	/**
	 * Spec 001 (lado de recepción): reensambla real, en vez de asumir siempre un solo frame -- ver
	 * {@link WireFraming.PartitionedAccumulator}. Si el primer frame ya trae el mensaje completo
	 * (el caso de siempre hasta ahora), el comportamiento es idéntico al de antes: un solo
	 * {@code feed} deja {@code isComplete()} en true de inmediato y no se lee ningún frame extra.
	 */
	private void handleOrdenTopoV(Frame frame, DataInputStream in) throws Exception {
		WireFraming.PartitionedAccumulator acc = new WireFraming.PartitionedAccumulator();
		acc.feed(frame.body(), sessionKey, sessionIv);
		int parts = 1;
		while (!acc.isComplete()) {
			Frame continuation = Frame.read(in);
			acc.feed(continuation.body(), sessionKey, sessionIv);
			parts++;
		}
		if (parts > 1) {
			logger.info("[SPEI] << OrdenTopoV reensamblado de {} frames (spec 001)", parts);
		}
		WireFraming.Unwrapped unwrapped = WireFraming.parseSignedPartitioned(acc.assembledPlaintext(), minosPublicKey);
		OrdenTopoVCodec.ParsedOrdenTopoV orden = OrdenTopoVCodec.parse(unwrapped.payload());
		store.logEvent(runId, "IN", "OrdenTopoV", frame.operation(), "recibido",
				"folioPack=" + orden.folioPack() + " ordenes=" + orden.orders().size()
						+ " firmaVerificada=" + unwrapped.signatureVerified(),
				frame.body());
		logger.info("[SPEI] << OrdenTopoV folioPack={}, {} orden(es), firma verificada={}",
				orden.folioPack(), orden.orders().size(), unwrapped.signatureVerified());

		List<AcuseReciboCodec.OrderError> errors = new ArrayList<>();
		for (OrdenTopoVCodec.Order order : orden.orders()) {
			OrderFieldValidator.OrderContext ctx = new OrderFieldValidator.OrderContext(
					order.paymentType(), order.trackingKey(), order.amount(),
					orden.entityCode(), orden.receptorEntityCode());
			String[] detailFields = OrdenTopoVCodec.splitDetailFields(order.detail());
			List<String> validationErrors = validator.validate(ctx, detailFields);
			if (validationErrors.isEmpty()) {
				logger.info("[SPEI]   orden folioInterno={} tipoPg={} claveRastreo={}: ACEPTADA",
						order.internalFolio(), order.paymentType(), order.trackingKey());
			} else {
				logger.warn("[SPEI]   orden folioInterno={} tipoPg={} claveRastreo={}: RECHAZADA -> {}",
						order.internalFolio(), order.paymentType(), order.trackingKey(), validationErrors);
				errors.add(new AcuseReciboCodec.OrderError(order.internalFolio(), (char) 1));
			}
		}

		char status = errors.isEmpty() ? AcuseReciboCodec.STATUS_ACCEPTED : AcuseReciboCodec.STATUS_REJECTED;
		byte[] acusePayload = AcuseReciboCodec.buildBody(
				orden.operationDate(), orden.folioPack(), orden.entityIndex(), orden.entityCode(), status, errors);
		byte[] acuseBody = WireFraming.withLengthPrefix(acusePayload);
		synchronized (writeLock) {
			Frame.of(SpeiProtocol.OP_ACUSERECIBO, acuseBody).writeTo(out);
		}
		store.logEvent(runId, "OUT", "AcuseRecibo", SpeiProtocol.OP_ACUSERECIBO,
				errors.isEmpty() ? "aceptado" : "rechazado", "erroresOrdenes=" + errors.size(), acuseBody);
		logger.info("[SPEI] >> AcuseRecibo folioPack={} status={} erroresOrdenes={}",
				orden.folioPack(), (int) status, errors.size());
	}

	// ---- Fase 5: envío manual de abonos (disparado desde Main vía consola) ----

	/** Manda un abono de prueba, válido o deliberadamente inválido. Ver README &sect;"Probar
	 *  Fase 5" para cómo dispararlo desde la consola del simulador. */
	public void sendTestAbono(boolean valid) {
		if (!alive) {
			logger.warn("[SPEI] No hay sesión SPEI viva todavía, no se puede mandar Abonos");
			return;
		}
		try {
			String detail = valid
					// nombreOrdenante|tipoCtaOrdenante|ctaOrdenante|rfcOrdenante|nombreBeneficiario|
					// tipoCtaBeneficiario|ctaBeneficiario|rfcBeneficiario|conceptoPg|iva|referenciaNumerica|referenciaCobranza
					? String.join("\0",
							"JUAN PEREZ GOMEZ", "40", "012180000123456789",
							"PEGJ800101H01", "MARIA LOPEZ RUIZ", "40", "012180000987654321",
							"", "PAGO DE PRUEBA SIMULADOR", "", "1234567", "")
					// rfcOrdenante deliberadamente inválido (no cumple RFC/CURP) para probar rechazo
					: String.join("\0",
							"JUAN PEREZ GOMEZ", "40", "012180000123456789",
							"RFC-INVALIDO!!", "MARIA LOPEZ RUIZ", "40", "012180000987654321",
							"", "PAGO DE PRUEBA SIMULADOR", "", "1234567", "");

			AbonosCodec.AbonoVSpec spec = new AbonosCodec.AbonoVSpec(
					LocalDate.now(), config.ownEntityIndex(), config.ownEntityCode(),
					config.minosEntityIndex(), config.minosEntityCode(),
					1, 0, false, new BigDecimal("100.00"), 1, "SIMU" + System.currentTimeMillis(),
					detail, valid ? AbonosCodec.SignatureMode.VALIDA : AbonosCodec.SignatureMode.VACIA);
			sendAbono(spec, valid ? "enviado-valido" : "enviado-invalido",
					valid ? "contenido válido" : "contenido deliberadamente inválido");
		} catch (Exception e) {
			logger.error("[SPEI] No fue posible mandar el abono de prueba: {}", e.getMessage(), e);
		}
	}

	/**
	 * Manda un abono de cualquier tipo de pago del catálogo ({@link mx.endcom.hermes.banxicosim.validation.PaymentType}),
	 * con clave de rastreo y modo de firma configurables -- generaliza {@link #sendTestAbono} más
	 * allá del tipo 01 hardcodeado, para las specs 002 (devoluciones), 004 (firmas) y 006 (folio
	 * duplicado). {@code trackingKey}, si es {@code null}, se autogenera igual que
	 * {@link #sendTestAbono}.
	 */
	public void sendCustomAbono(int paymentType, String trackingKey, java.util.Map<String, String> fields,
			AbonosCodec.SignatureMode signatureMode) throws Exception {
		if (!alive) {
			throw new IllegalStateException("No hay sesión SPEI viva todavía, no se puede mandar Abonos");
		}
		mx.endcom.hermes.banxicosim.validation.PaymentType type =
				mx.endcom.hermes.banxicosim.validation.PaymentType.byCode(paymentType);
		if (type == null) {
			throw new IllegalArgumentException("Tipo de pago " + paymentType + " fuera del catálogo real SPEI");
		}
		String detail = type.buildDetail(fields);
		String key = (trackingKey == null || trackingKey.isBlank())
				? "SIMU" + System.currentTimeMillis()
				: trackingKey;
		AbonosCodec.AbonoVSpec spec = new AbonosCodec.AbonoVSpec(
				LocalDate.now(), config.ownEntityIndex(), config.ownEntityCode(),
				config.minosEntityIndex(), config.minosEntityCode(),
				1, 0, false, new BigDecimal("100.00"), paymentType, key, detail, signatureMode);
		sendAbono(spec, "enviado", "tipoPg=" + paymentType + ", firma=" + signatureMode);
	}

	private void sendAbono(AbonosCodec.AbonoVSpec spec, String logResult, String logDescription) throws Exception {
		byte[] abonoV = AbonosCodec.buildAbonoV(spec, identity.privateKey());

		AbonosCodec.AbonoTRef abonoT = new AbonosCodec.AbonoTRef(
				config.minosEntityIndex(), config.minosEntityCode(), 1, (short) 1);
		byte[] payload = AbonosCodec.buildPayload(
				LocalDate.now(), 1, abonoT, new BigDecimal("100.00"), abonoV,
				new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO);

		// Spec 001 (lado de envío): con maxMessageLength por defecto (65535, ver SimConfig), esto
		// siempre produce exactamente 1 frame -- mismo comportamiento de siempre. Solo se parte en
		// varios si config.maxMessageLength() se baja a propósito para probar el reensamblado real
		// del lado de minos.
		java.util.List<byte[]> frames = WireFraming.buildEncryptedSignedPartitionedFrames(payload,
				identity.privateKey(), sessionKey, sessionIv, config.maxMessageLength());
		synchronized (writeLock) {
			for (byte[] frameBody : frames) {
				Frame.of(SpeiProtocol.OP_ABONOS, frameBody).writeTo(out);
			}
		}
		store.logEvent(runId, "OUT", "Abonos", SpeiProtocol.OP_ABONOS, logResult, null, frames.get(0));
		logger.info("[SPEI] >> Abonos ({}{})", logDescription,
				frames.size() > 1 ? ", partido en " + frames.size() + " frames (spec 001)" : "");
	}
}
