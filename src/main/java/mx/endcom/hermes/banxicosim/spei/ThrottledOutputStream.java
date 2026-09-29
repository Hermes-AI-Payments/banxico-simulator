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
		private final AtomicLong bytesPorSegundo;
		private double tokens;
		private long ultimoRellenoNanos;

		TokenBucket(long bytesPorSegundoInicial) {
			this.bytesPorSegundo = new AtomicLong(normalizar(bytesPorSegundoInicial));
			this.tokens = this.bytesPorSegundo.get();
			this.ultimoRellenoNanos = System.nanoTime();
		}

		void setBytesPorSegundo(long valor) {
			bytesPorSegundo.set(normalizar(valor));
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
			tokens = Math.min(limite, tokens + segundosTranscurridos * limite);
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
