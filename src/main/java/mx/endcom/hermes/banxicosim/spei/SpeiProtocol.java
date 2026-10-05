package mx.endcom.hermes.banxicosim.spei;

/**
 * Códigos de operación del socket SPEI principal, verificados contra
 * {@code core/spei/message/ToSpeiInputMessage.java} (los que minos reconoce como entrada, es
 * decir los que el simulador debe mandar) y las clases {@code core/spei/message/out/*Message.java}
 * (los que minos manda y el simulador debe parsear) del repo minos.
 */
public final class SpeiProtocol {

	private SpeiProtocol() {
	}

	/** Byte de "destino" en el encabezado. No se valida del lado de minos (ver spec técnica &sect;2),
	 *  se imita el mismo valor que usa minos (SPEI_SERVICE = 0x51) por plausibilidad. */
	public static final int DESTINATION = 0x51;

	// --- Mensajes que minos manda y el simulador recibe (parsea) ---
	public static final int OP_CONEXION = 16;
	public static final int OP_LOGIN = 1;
	public static final int OP_IAMALIVE = 7;
	public static final int OP_INICIO_SESION_CIFRADA = 220;
	public static final int OP_RESP_CLVSIM = 221;
	public static final int OP_ORDEN_TOPOV = 206;
	public static final int OP_DEADSRVR = 243;
	public static final int OP_SMTTYCLOSE = 244;
	public static final int OP_NOSERVICE = 252;

	// --- Mensajes que el simulador manda y minos recibe (parsea) ---
	public static final int OP_GREETING = 247;
	public static final int OP_SMLOGINREQ = 254;
	public static final int OP_ENSESION = 13;
	public static final int OP_CLVSIM = 80;
	public static final int OP_MSJCATALOGOS = 31;
	public static final int OP_ABONOS = 25;
	public static final int OP_ACUSERECIBO = 27;
	public static final int OP_AREYOUALIVE = 245;
	// Spec 014 -- verificados contra ToSpeiInputMessage.java (repo mki, vigente 2026-09-28; code/minos
	// local está desactualizado, ver minos_wire_protocol_verification).
	public static final int OP_CARGOS = 24;
	public static final int OP_LIQUIDACIONFINAL = 51;
}
