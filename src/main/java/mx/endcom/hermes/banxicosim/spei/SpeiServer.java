package mx.endcom.hermes.banxicosim.spei;

import java.net.ServerSocket;
import java.net.Socket;
import java.security.PublicKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mx.endcom.hermes.banxicosim.config.SimConfig;
import mx.endcom.hermes.banxicosim.crypto.SimulatorIdentity;
import mx.endcom.hermes.banxicosim.persistence.H2Store;

/**
 * Servidor TCP del socket SPEI principal (Fase 1). minos abre la conexión hacia este puerto.
 */
public final class SpeiServer implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(SpeiServer.class);

	private final int port;
	private final SimulatorIdentity identity;
	private final PublicKey minosPublicKey;
	private final SimConfig config;
	private final H2Store store;
	private final RedVariacionRegistry redVariacionRegistry;
	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final AtomicReference<SpeiSession> lastSession = new AtomicReference<>();
	private volatile boolean running = true;
	private ServerSocket serverSocket;

	public SpeiServer(int port, SimulatorIdentity identity, PublicKey minosPublicKey, SimConfig config,
			H2Store store, RedVariacionRegistry redVariacionRegistry) {
		this.port = port;
		this.identity = identity;
		this.minosPublicKey = minosPublicKey;
		this.config = config;
		this.store = store;
		this.redVariacionRegistry = redVariacionRegistry;
	}

	/** Sesión SPEI más reciente (viva o no) — usada por la consola del simulador para disparar
	 *  el envío de un abono de prueba (Fase 5). Suficiente para un simulador de un solo participante
	 *  bajo prueba a la vez; no está pensado para correr varias sesiones SPEI concurrentes útiles. */
	public SpeiSession lastSession() {
		return lastSession.get();
	}

	@Override
	public void run() {
		try (ServerSocket server = new ServerSocket(port)) {
			this.serverSocket = server;
			logger.info("[SPEI] Escuchando en el puerto {}", port);
			while (running) {
				Socket socket = server.accept();
				SpeiSession session = new SpeiSession(socket, identity, minosPublicKey, config, store,
						redVariacionRegistry);
				lastSession.set(session);
				pool.submit(session);
			}
		} catch (Exception e) {
			if (running) {
				logger.error("[SPEI] Servidor detenido con error: {}", e.getMessage(), e);
			}
		}
	}

	public void stop() {
		running = false;
		pool.shutdownNow();
		try {
			if (serverSocket != null) {
				serverSocket.close();
			}
		} catch (Exception ignored) {
			// cierre en curso
		}
	}
}
