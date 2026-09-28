package mx.endcom.hermes.banxicosim.spei;

import java.io.EOFException;
import java.io.IOException;
import java.time.Instant;

/**
 * Cómo y cuándo terminó una sesión SPEI/ARA (spec 013). Se registra como evento
 * {@code CierreSesion} en la bitácora y se expone en {@code GET /session} -- antes de esto el
 * cierre solo quedaba en el log de consola y una sesión {@code TERMINADA} no decía por qué.
 */
public record SessionClosure(Instant at, Cause cause, String detail) {

	public enum Cause {
		/** minos cerró la conexión: EOF, o mandó DeadSrvr/SmTtyClose/NoService. */
		MINOS_CERRO("minos-cerro"),
		/** El envío de {@code AreYouAlive} falló -- la conexión ya estaba rota de nuestro lado. */
		HEARTBEAT_FALLO("heartbeat-fallo"),
		/** Una prueba pidió cortar la sesión (spec 005, capa A). Reservado hasta implementarla. */
		CORTE_DELIBERADO("corte-deliberado"),
		/** Falla de I/O del socket (timeout, reset, broken pipe...). */
		ERROR_IO("error-io"),
		/** Cualquier otra excepción: trama malformada, firma o cifrado inválidos, etc. */
		ERROR_PROTOCOLO("error-protocolo"),
		/** Asignada al arrancar a corridas que quedaron sin cierre (ver H2Store): apagado o caída
		 *  del simulador -- el shutdown hook de H2 cierra la base antes de que se pueda escribir. */
		SIN_REGISTRO("sin-registro");

		private final String code;

		Cause(String code) {
			this.code = code;
		}

		/** Valor que se guarda en la bitácora y se expone por la API. */
		public String code() {
			return code;
		}
	}

	public static SessionClosure now(Cause cause, String detail) {
		return new SessionClosure(Instant.now(), cause, detail);
	}

	/** Clasifica la excepción que terminó la sesión, cuando nadie fijó la causa antes. */
	public static SessionClosure fromException(Exception e) {
		if (e instanceof EOFException) {
			return now(Cause.MINOS_CERRO, null);
		}
		String detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
		return now(e instanceof IOException ? Cause.ERROR_IO : Cause.ERROR_PROTOCOLO, detail);
	}
}
