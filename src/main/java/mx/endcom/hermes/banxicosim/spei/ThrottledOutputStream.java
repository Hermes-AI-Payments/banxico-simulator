package mx.endcom.hermes.banxicosim.spei;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spec 005 (capa A), decisión "Throttling asimétrico": envuelve el {@code OutputStream} real del
 * socket SPEI y aplica un límite de bytes/segundo tipo token-bucket, independiente del de lectura
 * ({@link ThrottledInputStream}) -- cada dirección tiene su propio {@link TokenBucket}.
 *
 * <p>Solo overridea {@link #write(byte[], int, int)}: {@code Frame.writeTo} manda el frame
 * completo con {@code out.write(byte[])} (ver {@code Frame.toBytes}), que {@link OutputStream}
 * resuelve contra la variante de rango -- nunca byte a byte. {@link #write(int)} existe solo
 * porque {@link OutputStream} lo exige.</p>
 */
final class ThrottledOutputStream extends OutputStream {

	/** Compartido entre {@link ThrottledOutputStream} y {@link ThrottledInputStream} -- un límite
	 *  por dirección, mutable en caliente cuando arranca/termina una variación de throttling (ver
	 *  {@link RedVariacionRegistry}). {@code Long.MAX_VALUE} = sin límite (no aplica pacing). */
	static final class TokenBucket {

		/** Tope de acumulación de tokens, independiente de la tasa configurada -- ver bug
		 *  2026-10-06 en {@link #rellenar(long)}: si el tope fuera igual a la tasa (como estaba
		 *  antes), un mensaje más grande que bytes/segundo nunca junta suficientes tokens y
		 *  {@link #consumir(int)} se queda esperando para siempre (confirmado en vivo contra
		 *  minosA real -- un Abonos de 896 bytes a 5 B/s nunca completó, ver specs/005 &sect;"Estado
		 *  de implementación"). 1 MiB es más que cualquier frame SPEI real, así que nunca bloquea
		 *  un mensaje legítimo, y sigue acotado (no crece sin límite tras una espera larga). */
		private static final double CAPACIDAD_MAXIMA = 1_048_576;

		private final AtomicLong bytesPorSegundo;
		private double tokens;
		private long ultimoRellenoNanos;

		TokenBucket(long bytesPorSegundoInicial) {
			this.bytesPorSegundo = new AtomicLong(normalizar(bytesPorSegundoInicial));
			this.tokens = this.bytesPorSegundo.get();
			this.ultimoRellenoNanos = System.nanoTime();
		}

		/** Cambia la tasa vigente. Si pasa de sin-límite (o de una tasa alta) a una tasa baja,
		 *  acota también {@code tokens} a la tasa nueva -- si no, el throttling recién armado
		 *  heredaría una ráfaga inicial grande (el remanente de antes de activarse) en vez de
		 *  empezar a limitar de inmediato. {@link #rellenar(long)} sigue permitiendo que, DESPUÉS
		 *  de esto, {@code tokens} crezca hasta {@link #CAPACIDAD_MAXIMA} mientras algo espera. */
		synchronized void setBytesPorSegundo(long valor) {
			long normalizado = normalizar(valor);
			bytesPorSegundo.set(normalizado);
			if (normalizado != Long.MAX_VALUE) {
				tokens = Math.min(tokens, normalizado);
			}
		}

		private static long normalizar(long v) {
			return v <= 0 ? Long.MAX_VALUE : v;
		}

		/** Bloquea (con {@link #wait(long)}, que libera el monitor durante la espera) hasta que
		 *  haya {@code n} tokens disponibles, o retorna de inmediato si no hay límite vigente. */
		synchronized void consumir(int n) throws IOException {
			long limite = bytesPorSegundo.get();
			if (limite == Long.MAX_VALUE || n <= 0) {
				return;
			}
			while (true) {
				rellenar(limite);
				if (tokens >= n) {
					tokens -= n;
					return;
				}
				double faltan = n - tokens;
				long esperaMs = Math.max(1, (long) Math.ceil(faltan * 1000.0 / limite));
				try {
					wait(esperaMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Throttling (spec 005) interrumpido");
				}
			}
		}

		private void rellenar(long limite) {
			long ahora = System.nanoTime();
			double segundosTranscurridos = (ahora - ultimoRellenoNanos) / 1_000_000_000.0;
			ultimoRellenoNanos = ahora;
			tokens = Math.min(CAPACIDAD_MAXIMA, tokens + segundosTranscurridos * limite);
		}
	}

	private final OutputStream real;
	private final TokenBucket limite;

	ThrottledOutputStream(OutputStream real, TokenBucket limite) {
		this.real = real;
		this.limite = limite;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		limite.consumir(len);
		real.write(b, off, len);
	}

	@Override
	public void flush() throws IOException {
		real.flush();
	}

	@Override
	public void close() throws IOException {
		real.close();
	}
}
