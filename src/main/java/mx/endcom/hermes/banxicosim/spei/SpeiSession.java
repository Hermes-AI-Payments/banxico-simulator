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
import mx.endcom.hermes.banxicosim.spei.messages.CargosCodec;
import mx.endcom.hermes.banxicosim.spei.messages.ClvSimCodec;
import mx.endcom.hermes.banxicosim.spei.messages.EnSesionCodec;
import mx.endcom.hermes.banxicosim.spei.messages.LiquidacionFinalCodec;
import mx.endcom.hermes.banxicosim.spei.messages.MsjCatalogosCodec;
import mx.endcom.hermes.banxicosim.spei.messages.OrdenTopoVCodec;
import mx.endcom.hermes.banxicosim.spei.messages.ReenvioCodec;
import mx.endcom.hermes.banxicosim.validation.MotivoRechazo;
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
	// Spec 005 (capa A): variaciones de red armadas desde la API de control -- ver
	// RedVariacionControl.beforeWrite en cada sitio de envío, y el envoltorio de streams en run().
	private final RedVariacionRegistry redVariacionRegistry;
	private final RedVariacionControl redVariacion;
	// Spec 014: rechazo forzado de una orden de OrdenTopoV -- ver RechazoForzadoRegistry.
	private final RechazoForzadoRegistry rechazoForzadoRegistry;
	// Spec 003: historial de bytes mandados en esta sesión, para poder atender un Reenvio real.
	// Corregido 2026-10-08 (regresión real contra minosA): NO se graba todo lo que sale por el
	// socket -- minos cuenta "processedBytes"/Reenvio solo sobre mensajes de CONTENIDO
	// (application.yml:msgSumanBytes en el repo mki: Cargos=24, AcuseRecibo=27,
	// LiquidacionFinal=51, entre otros que este simulador no manda), nunca sobre el handshake
	// (Greeting/SmLoginReq/EnSesion/ClvSim/MsjCatalogos). Reenviar el handshake hacía que minos
	// reprocesara EnSesion/MsjCatalogos a mitad de sesión viva (reset de claves AES, otro Reenvio
	// en bucle) -- veía códigos de operación corruptos y terminaba en Connection reset. Por eso ya
	// no se envuelve el OutputStream genérico (ver run()) -- se anota explícito en cada sitio de
	// envío que SÍ cuenta, ver OPCODES_CUENTAN_PARA_REENVIO abajo.
	private final SentHistory sentHistory = new SentHistory();

	/** Códigos de operación que SÍ cuentan para el historial de Reenvio -- subconjunto de
	 *  {@code msgSumanBytes} (mki/minos, application.yml) que este simulador realmente manda. */
	private static final java.util.Set<Integer> OPCODES_CUENTAN_PARA_REENVIO = java.util.Set.of(
			SpeiProtocol.OP_CARGOS, SpeiProtocol.OP_ACUSERECIBO, SpeiProtocol.OP_LIQUIDACIONFINAL,
			SpeiProtocol.OP_ABONOS);

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
			H2Store store, RedVariacionRegistry redVariacionRegistry, RechazoForzadoRegistry rechazoForzadoRegistry) {
		this.socket = socket;
		this.identity = identity;
		this.minosPublicKey = minosPublicKey;
		this.config = config;
		this.store = store;
		this.runId = store.newRun("SPEI", socket.getRemoteSocketAddress().toString());
		this.redVariacionRegistry = redVariacionRegistry;
		this.redVariacion = new RedVariacionControl(redVariacionRegistry);
		this.rechazoForzadoRegistry = rechazoForzadoRegistry;
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
				// Spec 005 (capa A): envuelve los streams reales del socket para throttling
				// asimétrico por dirección -- transparente (sin límite vigente) hasta que una
				// variación de tipo "throttling" cambia la tasa del TokenBucket compartido.
				DataInputStream in = new DataInputStream(
						new ThrottledInputStream(socket.getInputStream(), redVariacionRegistry.limiteEntrada()));
				DataOutputStream dataOut = new DataOutputStream(
						new ThrottledOutputStream(socket.getOutputStream(), redVariacionRegistry.limiteSalida()))) {
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
		sendConVariacion("Greeting", Frame.of(SpeiProtocol.OP_GREETING, new byte[0]));
		store.logEvent(runId, "OUT", "Greeting", SpeiProtocol.OP_GREETING, "enviado", null, null);
		phase = Phase.GREETING_ENVIADO;
		logger.info("[SPEI] >> Greeting");
	}

	// ---- Fase 2 ----

	private void sendSmLoginReq() throws Exception {
		sendConVariacion("SmLoginReq", Frame.of(SpeiProtocol.OP_SMLOGINREQ, new byte[0]));
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
		sendConVariacion("ClvSim", Frame.of(SpeiProtocol.OP_CLVSIM, clvSim.bytes()));
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
		sendConVariacion("EnSesion", Frame.of(SpeiProtocol.OP_ENSESION, body));
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
		sendConVariacion("MsjCatalogos", Frame.of(SpeiProtocol.OP_MSJCATALOGOS, body));
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
	 * en la spec técnica original ni en el arnés de verificación previo). Cada
	 * {@code spei.heartbeatIntervalMs} (spec 005; default 3000ms), bien debajo del límite de 6s.
	 */
	private void startHeartbeat() {
		heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "spei-heartbeat-" + runId);
			t.setDaemon(true);
			return t;
		});
		long intervalMs = config.heartbeatIntervalMs();
		heartbeat.scheduleAtFixedRate(() -> {
			try {
				sendConVariacion("AreYouAlive", Frame.of(SpeiProtocol.OP_AREYOUALIVE, new byte[0]));
				logger.debug("[SPEI] >> AreYouAlive (heartbeat)");
			} catch (Exception e) {
				logger.warn("[SPEI] Heartbeat falló, cerrando la sesión: {}", e.getMessage());
				heartbeat.shutdown();
				// Si ya no se puede escribir, la conexión está rota: sin cerrarla, la lectura puede
				// quedarse bloqueada indefinidamente si minos desapareció sin mandar FIN.
				requestClose(SessionClosure.Cause.HEARTBEAT_FALLO, e.getMessage());
			}
		}, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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
				case SpeiProtocol.OP_REENVIO -> handleReenvio(frame); // ReenvioMessage.MSG_CODE, ver ReenvioCodec
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

	/**
	 * Spec 003 -- reenvío real: {@code processedBytes} es una posición absoluta en el historial de
	 * bytes de CONTENIDO (ver {@link #OPCODES_CUENTAN_PARA_REENVIO} y {@link SentHistory}) que
	 * este simulador ha mandado en la sesión -- NO incluye el handshake (corregido 2026-10-08, ver
	 * la nota de {@link #sentHistory}). Si todavía hay algo después de esa posición, se reenvía tal
	 * cual (mismos bytes, sin re-cifrar ni re-firmar) antes de responder {@code FinReenvio}. El
	 * reenvío en sí no se vuelve a anotar en el historial (serían los mismos bytes contados dos
	 * veces).
	 */
	private void handleReenvio(Frame frame) throws Exception {
		byte[] plaintext = AesCipher.decrypt(frame.body(), sessionKey, sessionIv);
		ReenvioCodec.Reenvio reenvio = ReenvioCodec.parse(plaintext);
		store.logEvent(runId, "IN", "Reenvio", frame.operation(), "recibido",
				"bytesProcesados=" + reenvio.processedBytes(), frame.body());

		long total = sentHistory.length();
		long processed = Integer.toUnsignedLong(reenvio.processedBytes());
		if (processed < total) {
			byte[] pending = sentHistory.bytesFrom(processed);
			synchronized (writeLock) {
				out.write(pending);
				out.flush();
			}
			store.logEvent(runId, "OUT", "ReenvioBytes", 0, "reenviado",
					"bytesProcesados=" + processed + " bytesReenviados=" + pending.length, pending);
			logger.info("[SPEI] << Reenvio (bytesProcesados={}), reenvío real de {} bytes (spec 003)",
					processed, pending.length);
		} else {
			logger.info("[SPEI] << Reenvio (bytesProcesados={}), nada que reenviar (ya tiene todo lo mandado)",
					processed);
		}

		byte[] finReenvioPlain = ReenvioCodec.buildFinReenvioBody();
		byte[] finReenvioCipher = AesCipher.encrypt(finReenvioPlain, sessionKey, sessionIv);
		sendConVariacion("FinReenvio", Frame.of(SpeiProtocol.OP_FINREENVIO, finReenvioCipher));
		store.logEvent(runId, "OUT", "FinReenvio", SpeiProtocol.OP_FINREENVIO, "enviado", null, finReenvioCipher);
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
		// clavesRastreo en el detalle: hace que /test-runs/{id}/events sea buscable por clave de
		// rastreo directamente (trazabilidad cruzada con Judeca/Core falso, que ya la loguean en
		// cada línea) sin tener que decodificar rawHex a mano.
		String clavesRastreo = orden.orders().stream()
				.map(OrdenTopoVCodec.Order::trackingKey)
				.collect(java.util.stream.Collectors.joining(","));
		store.logEvent(runId, "IN", "OrdenTopoV", frame.operation(), "recibido",
				"folioPack=" + orden.folioPack() + " ordenes=" + orden.orders().size()
						+ " firmaVerificada=" + unwrapped.signatureVerified()
						+ " clavesRastreo=" + clavesRastreo,
				frame.body());
		logger.info("[SPEI] << OrdenTopoV folioPack={}, {} orden(es), firma verificada={}",
				orden.folioPack(), orden.orders().size(), unwrapped.signatureVerified());

		// Spec 014 -- precedencia entre los tres chequeos de una misma orden: rechazo forzado
		// primero (intención deliberada de quien prueba), clave duplicada segundo (regla real de
		// Banxico, más barata que correr el validador completo), validación real al final.
		List<AcuseReciboCodec.OrderError> errors = new ArrayList<>();
		List<CargosCodec.CargoEntry> aceptadasInmediato = new ArrayList<>();
		// Spec 015 -- claves de rastreo de las órdenes que sí entran en el Cargos de este
		// folioPack (modo inmediato), para que el Cargos quede trazable igual que OrdenTopoV/
		// AcuseRecibo arriba. Un paquete puede traer varias órdenes aceptadas a la vez -- por eso
		// es una lista, no una sola clave (ver conversación 2026-10-07 sobre por qué folioPack
		// sigue importando: Cargos liquida el PAQUETE, no una orden individual).
		List<String> clavesAceptadasInmediato = new ArrayList<>();
		BigDecimal montoAceptadoInmediato = BigDecimal.ZERO;
		String modoLiquidacion = config.cargosModoLiquidacion();
		for (OrdenTopoVCodec.Order order : orden.orders()) {
			RechazoForzado forzado = rechazoForzadoRegistry.activo()
					.filter(r -> r.coincideCon(order.trackingKey()))
					.orElse(null);
			if (forzado != null) {
				rechazoForzadoRegistry.consumir(forzado, "folioInterno=" + order.internalFolio()
						+ " claveRastreo=" + order.trackingKey());
				logger.warn("[SPEI]   orden folioInterno={} tipoPg={} claveRastreo={}: RECHAZADA (forzado por '{}', motivo={})",
						order.internalFolio(), order.paymentType(), order.trackingKey(), forzado.quien(), forzado.motivo().codigo());
				errors.add(new AcuseReciboCodec.OrderError(order.internalFolio(), (char) forzado.motivo().codigo()));
				continue;
			}
			boolean claveDuplicada = !order.trackingKey().isBlank()
					&& store.claveYaVista(orden.operationDate(), order.trackingKey());
			if (claveDuplicada) {
				logger.warn("[SPEI]   orden folioInterno={} tipoPg={} claveRastreo={}: RECHAZADA (clave de rastreo repetida)",
						order.internalFolio(), order.paymentType(), order.trackingKey());
				errors.add(new AcuseReciboCodec.OrderError(order.internalFolio(),
						(char) MotivoRechazo.CLAVE_RASTREO_REPETIDA.codigo()));
				continue;
			}
			OrderFieldValidator.OrderContext ctx = new OrderFieldValidator.OrderContext(
					order.paymentType(), order.trackingKey(), order.amount(),
					orden.entityCode(), orden.receptorEntityCode());
			String[] detailFields = OrdenTopoVCodec.splitDetailFields(order.detail());
			List<String> validationErrors = validator.validate(ctx, detailFields);
			if (validationErrors.isEmpty()) {
				logger.info("[SPEI]   orden folioInterno={} tipoPg={} claveRastreo={}: ACEPTADA",
						order.internalFolio(), order.paymentType(), order.trackingKey());
				store.marcarClaveVista(orden.operationDate(), order.trackingKey());
				// Spec 014 &sect;4 -- liquidación (Cargos): en modo acumulado solo se persiste en
				// H2 (POST /pagos/cargos/liquidar-lote decide cuándo mandarlo); en modo inmediato
				// (default) se junta aquí mismo y se manda un Cargos al terminar este OrdenTopoV.
				if ("acumulado".equalsIgnoreCase(modoLiquidacion)) {
					store.agregarCargoPendiente(orden.operationDate(), orden.entityIndex(), orden.entityCode(),
							orden.folioPack(), order.internalFolio(), order.amount());
				} else {
					aceptadasInmediato.add(new CargosCodec.CargoEntry(
							orden.entityIndex(), orden.entityCode(), orden.folioPack(), order.internalFolio()));
					clavesAceptadasInmediato.add(order.trackingKey());
					montoAceptadoInmediato = montoAceptadoInmediato.add(order.amount());
				}
			} else {
				MotivoRechazo motivo = MotivoRechazo.clasificar(validationErrors.get(0));
				logger.warn("[SPEI]   orden folioInterno={} tipoPg={} claveRastreo={}: RECHAZADA (motivo={}) -> {}",
						order.internalFolio(), order.paymentType(), order.trackingKey(), motivo.codigo(), validationErrors);
				errors.add(new AcuseReciboCodec.OrderError(order.internalFolio(), (char) motivo.codigo()));
			}
		}

		char status = errors.isEmpty() ? AcuseReciboCodec.STATUS_ACCEPTED : AcuseReciboCodec.STATUS_REJECTED;
		byte[] acusePayload = AcuseReciboCodec.buildBody(
				orden.operationDate(), orden.folioPack(), orden.entityIndex(), orden.entityCode(), status, errors);
		byte[] acuseBody = WireFraming.withLengthPrefix(acusePayload);
		sendConVariacion("AcuseRecibo", Frame.of(SpeiProtocol.OP_ACUSERECIBO, acuseBody));
		// Mismo motivo que en el log de OrdenTopoV arriba: resultado por clave de rastreo,
		// buscable en /test-runs/{id}/events sin decodificar rawHex.
		java.util.Map<Short, Character> motivoPorFolio = new java.util.HashMap<>();
		for (AcuseReciboCodec.OrderError e : errors) {
			motivoPorFolio.put(e.internalFolio(), e.errorCode());
		}
		String resultadoPorClave = orden.orders().stream()
				.map(o -> o.trackingKey() + ":" + (motivoPorFolio.containsKey(o.internalFolio())
						? "RECHAZADA(" + (int) (char) motivoPorFolio.get(o.internalFolio()) + ")"
						: "ACEPTADA"))
				.collect(java.util.stream.Collectors.joining(","));
		store.logEvent(runId, "OUT", "AcuseRecibo", SpeiProtocol.OP_ACUSERECIBO,
				errors.isEmpty() ? "aceptado" : "rechazado",
				"erroresOrdenes=" + errors.size() + " resultadoPorClave=" + resultadoPorClave, acuseBody);
		logger.info("[SPEI] >> AcuseRecibo folioPack={} status={} erroresOrdenes={}",
				orden.folioPack(), (int) status, errors.size());

		if (!aceptadasInmediato.isEmpty()) {
			sendCargos(orden.folioPack(), aceptadasInmediato, montoAceptadoInmediato, clavesAceptadasInmediato);
		}
	}

	/**
	 * Spec 014 &sect;4 -- manda {@code Cargos} con las entradas dadas y actualiza el saldo del día
	 * operativo (persistido en H2 -- sobrevive una reconexión de la sesión SPEI, ver
	 * {@code H2Store.saldoDelDia}). Público porque también lo dispara la API de control
	 * directamente (modo manual, y el flush de {@code POST /pagos/cargos/liquidar-lote} en modo
	 * acumulado), no solo el disparo automático de {@link #handleOrdenTopoV} en modo inmediato.
	 */
	public void sendCargos(int folio, List<CargosCodec.CargoEntry> entries, BigDecimal montoTotal) throws Exception {
		sendCargos(folio, entries, montoTotal, null);
	}

	/**
	 * Spec 015 -- misma lógica que {@link #sendCargos(int, List, BigDecimal)}, con las claves de
	 * rastreo de las órdenes liquidadas para que el evento quede trazable (ver
	 * {@code specs/015-trazabilidad-cruzada.md}). {@code cvesRastreo} es {@code null} para los
	 * disparos manuales/de lote (API de control) -- ahí no hay órdenes reales detrás, no tiene
	 * sentido inventar claves.
	 */
	public void sendCargos(int folio, List<CargosCodec.CargoEntry> entries, BigDecimal montoTotal,
			List<String> cvesRastreo) throws Exception {
		if (!alive) {
			throw new IllegalStateException("No hay sesión SPEI viva todavía, no se puede mandar Cargos");
		}
		H2Store.SaldoDia saldoActual = store.saldoDelDia(operationalDate, config.cargosBalanceInicial(),
				config.cargosReservedBalanceInicial());
		BigDecimal nuevoBalance = saldoActual.balance().add(montoTotal);
		store.actualizarSaldo(operationalDate, nuevoBalance, saldoActual.reservedBalance());

		byte[] payload = CargosCodec.buildPayload(operationalDate, folio, entries, montoTotal,
				nuevoBalance, saldoActual.reservedBalance());
		List<byte[]> frames = WireFraming.buildEncryptedSignedPartitionedFrames(payload,
				identity.privateKey(), sessionKey, sessionIv, config.maxMessageLength());
		synchronized (writeLock) {
			for (byte[] frameBody : frames) {
				Frame frame = Frame.of(SpeiProtocol.OP_CARGOS, frameBody);
				frame.writeTo(out);
				byte[] bytes = frame.toBytes();
				sentHistory.append(bytes, 0, bytes.length);
			}
		}
		String detalle = "folio=" + folio + " entradas=" + entries.size() + " monto=" + montoTotal
				+ " balance=" + nuevoBalance;
		if (cvesRastreo != null && !cvesRastreo.isEmpty()) {
			detalle += " clavesRastreo=" + String.join(",", cvesRastreo);
		}
		store.logEvent(runId, "OUT", "Cargos", SpeiProtocol.OP_CARGOS, "enviado", detalle, frames.get(0));
		logger.info("[SPEI] >> Cargos folio={} entradas={} monto={} balance={}",
				folio, entries.size(), montoTotal, nuevoBalance);
	}

	/** Spec 014 &sect;5 -- cierre de día operativo. Solo disparable manualmente
	 *  ({@code POST /dia/cerrar}) -- no tiene sentido simular un cron de las 18:00 dentro de una
	 *  herramienta de pruebas bajo demanda. {@code folio} no tiene semántica documentada en
	 *  ningún lado para este mensaje (a diferencia de {@code Abonos}/{@code Cargos}, donde sí se
	 *  ata al {@code folioPack} de origen) -- se manda {@code 0}. */
	public void sendLiquidacionFinal(BigDecimal montoFinal) throws Exception {
		if (!alive) {
			throw new IllegalStateException("No hay sesión SPEI viva todavía, no se puede mandar LiquidacionFinal");
		}
		byte[] payload = LiquidacionFinalCodec.buildPayload(operationalDate, 0,
				config.ownEntityIndex(), config.ownEntityCode(), montoFinal);
		byte[] body = WireFraming.encryptSession(payload, sessionKey, sessionIv);
		synchronized (writeLock) {
			Frame frame = Frame.of(SpeiProtocol.OP_LIQUIDACIONFINAL, body);
			frame.writeTo(out);
			byte[] bytes = frame.toBytes();
			sentHistory.append(bytes, 0, bytes.length);
		}
		store.logEvent(runId, "OUT", "LiquidacionFinal", SpeiProtocol.OP_LIQUIDACIONFINAL, "enviado",
				"montoFinal=" + montoFinal, body);
		logger.info("[SPEI] >> LiquidacionFinal montoFinal={}", montoFinal);
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

		// Spec 005 (capa A): "Abonos" es el único de los 9 puntos que puede mandarse partido en
		// varios frames (spec 001) -- beforeWrite se llama una vez por abono lógico, no por
		// fragmento. Si hay duplicación, se reenvía la secuencia COMPLETA de frames (no un frame
		// suelto), para que sea fiel a "el mismo abono llegó dos veces" sin desalinear el
		// reensamblado de minos.
		RedVariacionControl.Decision decision = redVariacion.beforeWrite("Abonos");
		if (decision.retrasoMs() > 0) {
			Thread.sleep(decision.retrasoMs());
		}
		synchronized (writeLock) {
			for (byte[] frameBody : frames) {
				Frame frame = Frame.of(SpeiProtocol.OP_ABONOS, frameBody);
				frame.writeTo(out);
				byte[] bytes = frame.toBytes();
				sentHistory.append(bytes, 0, bytes.length);
			}
			if (decision.duplicar()) {
				for (byte[] frameBody : frames) {
					Frame frame = Frame.of(SpeiProtocol.OP_ABONOS, frameBody);
					frame.writeTo(out);
					byte[] bytes = frame.toBytes();
					sentHistory.append(bytes, 0, bytes.length);
				}
			}
		}
		if (decision.cortarDespues()) {
			logger.warn("[SPEI] Corte deliberado (spec 005) tras Abonos: {}", decision.detalleCorte());
			requestClose(SessionClosure.Cause.CORTE_DELIBERADO, decision.detalleCorte());
		}
		store.logEvent(runId, "OUT", "Abonos", SpeiProtocol.OP_ABONOS, logResult, null, frames.get(0));
		logger.info("[SPEI] >> Abonos ({}{}{})", logDescription,
				frames.size() > 1 ? ", partido en " + frames.size() + " frames (spec 001)" : "",
				decision.duplicar() ? ", duplicado (spec 005)" : "");
	}

	/** Envuelve el envío de un frame con la decisión de la variación de red activa (spec 005,
	 *  capa A): duerme el retraso FUERA de {@code writeLock} (para no bloquear otros envíos
	 *  concurrentes, p. ej. el heartbeat), manda el frame (y su duplicado, si aplica, DENTRO del
	 *  lock para que las dos copias salgan sin nada intercalado), y corta la sesión DESPUÉS de
	 *  soltar el lock si la variación lo pide. Ver spec 005 &sect;"Diseño de implementación de
	 *  Capa A". {@code sendAbono} no usa este helper porque puede mandar varios frames a la vez
	 *  (spec 001) -- ver su propio manejo arriba. */
	private void sendConVariacion(String puntoDesnudo, Frame frame) throws Exception {
		RedVariacionControl.Decision decision = redVariacion.beforeWrite(puntoDesnudo);
		if (decision.retrasoMs() > 0) {
			Thread.sleep(decision.retrasoMs());
		}
		boolean cuenta = OPCODES_CUENTAN_PARA_REENVIO.contains(frame.operation());
		synchronized (writeLock) {
			frame.writeTo(out);
			if (cuenta) {
				byte[] bytes = frame.toBytes();
				sentHistory.append(bytes, 0, bytes.length);
			}
			if (decision.duplicar()) {
				frame.writeTo(out);
				if (cuenta) {
					byte[] bytes = frame.toBytes();
					sentHistory.append(bytes, 0, bytes.length);
				}
			}
		}
		if (decision.cortarDespues()) {
			logger.warn("[SPEI] Corte deliberado (spec 005) tras {}: {}", puntoDesnudo, decision.detalleCorte());
			requestClose(SessionClosure.Cause.CORTE_DELIBERADO, decision.detalleCorte());
		}
	}
}
