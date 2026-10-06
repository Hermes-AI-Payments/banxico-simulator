package mx.endcom.hermes.banxicosim.spei;

import java.util.Arrays;

/**
 * Historial de bytes mandados por Banxico (el simulador) en una sesión SPEI, para servir
 * {@code Reenvio} (spec 003): minos pregunta desde qué posición necesita que se reenvíe, y
 * Banxico debe reenviar exactamente los bytes que ya mandó después de esa posición.
 *
 * <p>Decisiones de diseño (spec 003 &sect;"Preguntas abiertas", resueltas 2026-10-06 al construir
 * el arnés de pruebas Java -- ver {@code specs/003-reenvio.md} &sect;"Estado de implementación"):</p>
 * <ul>
 *   <li><b>Tamaño del historial:</b> se retiene la sesión completa mientras quepa en un tope de
 *       {@value #MAX_BYTES} -- de sobra para cualquier sesión de prueba razonable. Si se excede,
 *       se descartan los bytes más antiguos (ventana deslizante, se desplaza {@link #base}); un
 *       {@code Reenvio} que pida una posición ya descartada recibe, en su lugar, todo lo que
 *       sigue quedando (mejor esfuerzo, no error) -- caso de borde que no debería ocurrir en una
 *       sesión de prueba corta.</li>
 *   <li><b>Qué significa "reenviar":</b> repetir los bytes TAL CUAL se mandaron la primera vez
 *       (mismo cifrado, misma firma de cada mensaje) -- {@code Reenvio} opera al nivel del flujo
 *       de bytes del socket, no al nivel de mensajes individuales, así que no hay ningún motivo
 *       para re-cifrar o re-firmar algo que el simulador ya mandó una vez.</li>
 * </ul>
 */
final class SentHistory {

	/** Tope de retención -- ver nota de clase. 16 MiB de sobra para una sesión de prueba. */
	private static final int MAX_BYTES = 16 * 1024 * 1024;

	private byte[] buffer = new byte[0];
	/** Posición absoluta (desde el primer byte mandado en la sesión) del primer byte que sigue
	 *  presente en {@link #buffer} -- avanza si se descarta la parte más vieja del historial. */
	private long base = 0;

	synchronized void append(byte[] data, int offset, int length) {
		byte[] next = new byte[buffer.length + length];
		System.arraycopy(buffer, 0, next, 0, buffer.length);
		System.arraycopy(data, offset, next, buffer.length, length);
		buffer = next;
		if (buffer.length > MAX_BYTES) {
			int drop = buffer.length - MAX_BYTES;
			buffer = Arrays.copyOfRange(buffer, drop, buffer.length);
			base += drop;
		}
	}

	/** Total de bytes mandados en la sesión hasta ahora (posición absoluta del siguiente byte que
	 *  se mande). */
	synchronized long length() {
		return base + buffer.length;
	}

	/** Todo lo mandado desde la posición absoluta {@code processedBytes} hasta el final -- si esa
	 *  posición ya no está retenida (ver {@link #base}), regresa desde lo más viejo que sigue
	 *  disponible en vez de fallar. */
	synchronized byte[] bytesFrom(long processedBytes) {
		long from = Math.max(processedBytes, base);
		int start = (int) (from - base);
		if (start >= buffer.length) {
			return new byte[0];
		}
		return Arrays.copyOfRange(buffer, start, buffer.length);
	}
}
