package mx.endcom.hermes.banxicosim.validation;

/**
 * Spec 014 -- catálogo de motivos de rechazo/devolución de una orden SPEI (único catálogo real
 * disponible en toda la documentación: {@code HERMES-MKI-VOBEDA/01_Conceptos_Transversales_SPEI/
 * Catalogo_Devoluciones.md}). Se usa como el byte {@code codeErrors} de {@code AcuseRecibo}.
 *
 * <p>Ningún documento ni código de minos mapea estos motivos a las fallas concretas que reporta
 * {@link OrderFieldValidator} -- es un hueco real, no algo verificable. {@link #clasificar} es la
 * decisión de diseño tomada para cerrarlo (ver specs/014, &sect;1): un pequeño grupo de fallas
 * tiene un motivo específico y exacto; todo lo demás (longitud excedida, formato/regex inválido,
 * catálogos sin motivo propio como institución o causa de devolución) cae en
 * {@link #CARACTER_INVALIDO} como motivo genérico "contenido no válido".</p>
 */
public enum MotivoRechazo {

	CUENTA_INEXISTENTE(1),
	CUENTA_BLOQUEADA(2),
	CUENTA_CANCELADA(3),
	CUENTA_OTRA_DIVISA(5),
	CUENTA_NO_PERTENECE_RECEPTOR(6),
	FALTA_INFORMACION_MANDATORIA(14),
	TIPO_PAGO_ERRONEO(15),
	TIPO_OPERACION_ERRONEA(16),
	TIPO_CUENTA_NO_CORRESPONDE(17),
	CARACTER_INVALIDO(19),
	EXCEDE_LIMITE_SALDO_AUTORIZADO(20),
	EXCEDE_LIMITE_ABONOS_MES(21),
	NUMERO_CELULAR_NO_REGISTRADO(22),
	CUENTA_ADICIONAL_NO_RECIBE_PAGOS(23),
	ESTRUCTURA_INFO_ADICIONAL_INCORRECTA(24),
	FALTA_INSTRUCCION_DISPERSAR(25),
	RESOLUCION_CONVENIO_COLABORACION(26),
	PAGO_OPCIONAL_NO_ACEPTADO(27),
	TIPO_PAGO_CODI_SIN_NOTIFICACION(28),
	CLAVE_RASTREO_REPETIDA(30);

	private final int codigo;

	MotivoRechazo(int codigo) {
		this.codigo = codigo;
	}

	public int codigo() {
		return codigo;
	}

	public static MotivoRechazo porCodigo(int codigo) {
		for (MotivoRechazo m : values()) {
			if (m.codigo == codigo) {
				return m;
			}
		}
		throw new IllegalArgumentException("Motivo de rechazo no reconocido: " + codigo);
	}

	/**
	 * Infiere el motivo a partir del primer mensaje que {@link OrderFieldValidator#validate} haya
	 * reportado para una orden (mismo criterio documentado en specs/014 &sect;1: una orden con
	 * varias fallas reporta solo el primer motivo, en el mismo orden en que ya las revisa
	 * {@code validate}). Los patrones de texto de abajo se revisaron contra cada sitio real de
	 * {@code errors.add(...)} en {@link OrderFieldValidator} -- ninguno de ellos colisiona con otro.
	 */
	public static MotivoRechazo clasificar(String mensaje) {
		if (mensaje.contains(": esta vacio")) {
			return FALTA_INFORMACION_MANDATORIA;
		}
		if (mensaje.startsWith("tipoPg ") && mensaje.contains("fuera del catalogo")) {
			return TIPO_PAGO_ERRONEO;
		}
		if (mensaje.contains("tipo de cuenta") && mensaje.contains("no esta en el catalogo")) {
			return TIPO_CUENTA_NO_CORRESPONDE;
		}
		if (mensaje.startsWith("tipoOperacion:") && mensaje.contains("no esta en el catalogo")) {
			return TIPO_OPERACION_ERRONEA;
		}
		if (mensaje.contains("se esperaban") && mensaje.contains("campos de detalle")) {
			return ESTRUCTURA_INFO_ADICIONAL_INCORRECTA;
		}
		// Fallback genérico: longitud excedida, formato/regex inválido, catálogos sin motivo
		// propio (institución, causa de devolución), y cualquier otra falla de contenido.
		return CARACTER_INVALIDO;
	}
}
